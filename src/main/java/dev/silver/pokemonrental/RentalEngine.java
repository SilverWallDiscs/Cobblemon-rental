package dev.silver.pokemonrental;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/** Single-threaded, server-authoritative rental coordinator; use only on server thread. */
public final class RentalEngine {
    private final MinecraftServer server;
    private final RentalRepository db;
    private final RentalConfig config;
    private final CobbleDollarsEconomy money = new CobbleDollarsEconomy();
    private final Set<String> warned = new HashSet<>();

    public RentalEngine(MinecraftServer server, RentalRepository db, RentalConfig config) { this.server = server; this.db = db; this.config = config; }
    public RentalConfig config() { return config; }
    public RentalRepository database() { return db; }
    private RegistryAccess registry() { return server.registryAccess(); }
    public Pokemon partyPokemon(ServerPlayer player, int slot) { return Cobblemon.INSTANCE.getStorage().getParty(player).get(slot - 1); }
    public List<RentalListing> marketplace() { return db.all().stream().filter(RentalListing::available).sorted(Comparator.comparing(x -> x.id)).collect(Collectors.toList()); }
    public List<RentalListing> byOwner(UUID id) { return db.all().stream().filter(x -> x.ownerId.equals(id) && (!x.originalReturned || (x.renterId != null && !x.payoutClaimed))).sorted(Comparator.comparing((RentalListing x) -> x.id).reversed()).collect(Collectors.toList()); }
    public List<RentalListing> byRenter(UUID id) { return db.all().stream().filter(x -> id.equals(x.renterId) && x.active()).collect(Collectors.toList()); }
    public boolean isRentalPokemon(Pokemon p) { return db.wasRental(p.getUuid()); }
    /** Reject expired rental entities if chunks load AFTER their lease was cleaned up. */
    public boolean shouldDiscardTemporaryEntity(UUID pokemonId) {
        if (!db.wasRental(pokemonId)) return false;
        for (RentalListing r : db.all()) {
            if (pokemonId.equals(r.temporaryPokemonId) && r.active()) return false;
        }
        return true;
    }

    private static String money(long amount) { return "$" + String.format(Locale.US, "%,d", amount); }
    private void msg(ServerPlayer player, String text) { player.sendSystemMessage(Component.literal("§b[Pokémon Rental] §f" + text)); }
    private void warn(String key, Exception e) { if (warned.add(key)) PokemonRentalMod.LOGGER.error("Rental requires admin review: " + key, e); }

    public boolean list(ServerPlayer owner, int slot, long price, int minutes) {
        if (slot < 1 || slot > 6 || price < 1 || price > config.maxListingPrice || minutes < config.minRentalMinutes || minutes > config.maxRentalMinutes) return false;
        Pokemon original = partyPokemon(owner, slot);
        if (original == null || db.wasRental(original.getUuid())) { msg(owner, "No eligible Pokémon in that slot."); return false; }
        if (db.all().stream().anyMatch(r -> r.originalPokemonId.equals(original.getUuid()) && !r.originalReturned)) {
            msg(owner, "That Pokémon is already in rental escrow."); return false;
        }
        RentalListing listing = new RentalListing();
        listing.id = UUID.randomUUID().toString(); listing.ownerId = owner.getUUID(); listing.originalPokemonId = original.getUuid();
        listing.species = original.getSpecies().getName(); listing.level = original.getLevel(); listing.price = price;
        listing.minutes = minutes; listing.state = RentalListing.State.PREPARING;
        try {
            original.recall();
            db.storeOriginal(listing, original, registry()); // durable FIRST
            db.add(listing); // recovery knows the snapshot exists BEFORE removing from Cobblemon storage
            PlayerPartyStore store = Cobblemon.INSTANCE.getStorage().getParty(owner);
            if (!store.remove(original)) { msg(owner, "Couldn't remove the Pokémon from its original slot. Escrow is pending reconciliation."); return false; }
            listing.state = RentalListing.State.LISTED;
            db.save();
            msg(owner, "Listed " + listing.species + " for " + money(price) + " / " + minutes + " minutes.");
            return true;
        } catch (Exception e) { warn("list:" + listing.id, e); msg(owner, "Listing paused for safety. Contact an administrator."); return false; }
    }

    public boolean rent(ServerPlayer buyer, String id) {
        RentalListing r = db.find(id);
        if (r == null || !r.available() || r.owns(buyer.getUUID())) return false;
        if (!hasSpace(buyer.getUUID())) { msg(buyer, "Make room in your party or PC first."); return false; }
        BigInteger before;
        try { before = money.balance(buyer); }
        catch (Exception e) { warn("economy-read", e); msg(buyer, "CobbleDollars API is unavailable; purchase cancelled."); return false; }
        if (before.compareTo(BigInteger.valueOf(r.price)) < 0) { msg(buyer, "Not enough CobbleDollars."); return false; }
        // The serialized PAYMENT_PENDING record forms the durable write-ahead journal.
        // A crash/exception after this point is NOT automatically retried: admin must reconcile money.
        r.state = RentalListing.State.PAYMENT_PENDING; r.renterId = buyer.getUUID();
        r.temporaryPokemonId = UUID.randomUUID(); r.debitBalanceBefore = before.toString();
        db.recordCopy(r.temporaryPokemonId);
        boolean paid;
        try { paid = money.withdraw(buyer, r.price, before); }
        catch (Exception e) { r.state = RentalListing.State.PAYMENT_REVIEW; db.save(); warn("charge:" + r.id, e); msg(buyer, "Payment needs admin review. Do not retry."); return false; }
        if (!paid) {
            // If balance changed, consider it uncertain and do not retry or grant a copy.
            try {
                if (!money.balance(buyer).equals(before)) {
                    r.state = RentalListing.State.PAYMENT_REVIEW; db.save();
                    msg(buyer, "Payment is under admin review."); return false;
                }
            } catch (Exception e) { r.state = RentalListing.State.PAYMENT_REVIEW; db.save(); warn("charge:" + r.id,e); return false; }
            r.state = RentalListing.State.LISTED; r.renterId = null;
            r.temporaryPokemonId = null; r.debitBalanceBefore = null; db.save();
            msg(buyer, "Balance changed or payment failed. No charge was made."); return false;
        }
        r.startsAt = System.currentTimeMillis();
        r.expiresAt = r.startsAt + r.minutes * 60_000L;
        r.state = RentalListing.State.ACTIVE;
        db.save(); // Credited only after verified CobbleDollars deduction.
        if (!giveCopy(buyer, r)) msg(buyer, "Rent paid, but delivery is pending. Free a slot and run /pokerental again.");
        else msg(buyer, "Rental active! Expires " + new Date(r.expiresAt) + ". No held items are transferred.");
        ServerPlayer owner = server.getPlayerList().getPlayer(r.ownerId);
        if (owner != null) msg(owner, r.species + " was rented for " + money(r.price) + ". Earnings are claimable.");
        return true;
    }

    /** Keeps the same temporary UUID after crashes to avoid re-granting copies. */
    private boolean giveCopy(ServerPlayer renter, RentalListing r) {
        if (r.temporaryPokemonId == null || !r.active()) return false;
        if (hasPokemon(r.renterId, r.temporaryPokemonId)) return true;
        if (!hasSpace(renter.getUUID())) return false;
        try {
            Pokemon copy = db.rentalCopy(r, registry());
            if (!Cobblemon.INSTANCE.getStorage().getParty(renter).add(copy)) return false;
            return true;
        } catch (Exception e) { warn("delivery:" + r.id, e); return false; }
    }
    public void onLogin(ServerPlayer player) {
        for (RentalListing r : db.all()) {
            if (r.state == RentalListing.State.ACTIVE && r.renterId.equals(player.getUUID()) && r.active()) giveCopy(player, r);
        }
        tick();
    }
    private boolean hasSpace(UUID user) {
        try {
            return Cobblemon.INSTANCE.getStorage().getParty(user, registry()).getFirstAvailablePosition() != null ||
                Cobblemon.INSTANCE.getStorage().getPC(user, registry()).getFirstAvailablePosition() != null;
        } catch (Exception e) { return false; }
    }
    private boolean hasPokemon(UUID owner, UUID pokemonId) {
        if (pokemonId == null) return false;
        return Cobblemon.INSTANCE.getStorage().getParty(owner, registry()).get(pokemonId) != null ||
               Cobblemon.INSTANCE.getStorage().getPC(owner, registry()).get(pokemonId) != null;
    }
    private void strip(UUID owner, UUID pokemonId) {
        if (pokemonId == null) return;
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(owner, registry());
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(owner, registry());
        Pokemon p;
        while ((p = party.get(pokemonId)) != null) { p.recall(); if (!party.remove(p)) break; }
        while ((p = pc.get(pokemonId)) != null) { p.recall(); if (!pc.remove(p)) break; }
    }
    /** May be called only after expiry. Scans offline renter stores and online users (including PC and pasture-backed PC entries). */
    public boolean expire(RentalListing r) {
        if (r.state != RentalListing.State.ACTIVE || System.currentTimeMillis() < r.expiresAt) return false;
        try {
            strip(r.renterId, r.temporaryPokemonId);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) strip(p.getUUID(), r.temporaryPokemonId);
            r.state = RentalListing.State.EXPIRED; db.save();
            ServerPlayer owner = server.getPlayerList().getPlayer(r.ownerId);
            if (owner != null) msg(owner, r.species + " rental ended. Claim your original in /pokerental.");
            return true;
        } catch (Exception e) { warn("expiry:" + r.id, e); return false; }
    }
    public void tick() {
        for (RentalListing r : List.copyOf(db.all())) {
            if (r.state == RentalListing.State.PREPARING) {
                try {
                    db.original(r, registry()); // never remove Pokémon without a readable original
                    strip(r.ownerId, r.originalPokemonId);
                    r.state = RentalListing.State.LISTED; db.save();
                } catch (Exception e) { warn("prepare:" + r.id, e); }
            }
            // Reconcile post-crash duplicate original: index may have been saved
            // after listing while Cobblemon's party file was not yet flushed.
            // Once escrow is authoritative, the original UUID must not exist
            // in a player store until the owner formally claims it.
            if (!r.originalReturned && r.state != RentalListing.State.PREPARING) {
                try {
                    if (hasPokemon(r.ownerId, r.originalPokemonId)) {
                        db.original(r, registry()); // escrow validation before removal
                        strip(r.ownerId, r.originalPokemonId);
                    }
                } catch (Exception e) { warn("original-reconcile:" + r.id, e); }
            }
            if (r.state == RentalListing.State.ACTIVE && !r.active()) expire(r);
        }
    }
    /** Return the exact immutable escrow snapshot, not a modified rental copy. */
    public boolean returnOriginal(ServerPlayer owner, String id) {
        RentalListing r = db.find(id);
        if (r == null || !r.owns(owner.getUUID()) || r.originalReturned) return false;
        if (r.state == RentalListing.State.ACTIVE) {
            if (!expire(r)) { msg(owner, "This rental hasn't finished or cleanup is incomplete."); return false; }
        }
        if (r.state != RentalListing.State.EXPIRED && r.state != RentalListing.State.LISTED) return false;
        if (!hasSpace(owner.getUUID())) { msg(owner, "Make room in your party or PC first."); return false; }
        try {
            if (r.temporaryPokemonId != null) {
                strip(r.renterId, r.temporaryPokemonId);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) strip(p.getUUID(), r.temporaryPokemonId);
            }
            if (!hasPokemon(owner.getUUID(), r.originalPokemonId)) {
                Pokemon original = db.original(r, registry());
                if (!original.getUuid().equals(r.originalPokemonId)) throw new IllegalStateException("Escrow UUID mismatch");
                if (!Cobblemon.INSTANCE.getStorage().getParty(owner).add(original)) return false;
            }
            r.originalReturned = true;
            r.state = RentalListing.State.RETURNED; db.save();
            msg(owner, "Your original " + r.species + " has been returned (with its original item and stats)."); return true;
        } catch (Exception e) { warn("return:" + r.id, e); msg(owner, "Return needs admin review; escrow was not deleted."); return false; }
    }
    /** Payout ledger is per-rental and blocks retry on uncertain balance commit. */
    public boolean claimEarnings(ServerPlayer owner, RentalListing r) {
        if (r == null || !r.owns(owner.getUUID()) || r.payoutClaimed || r.payoutReview || r.price < 1 ||
                r.renterId == null || (r.state != RentalListing.State.ACTIVE && r.state != RentalListing.State.EXPIRED && r.state != RentalListing.State.RETURNED)) return false;
        try {
            BigInteger before = money.balance(owner);
            r.payoutBalanceBefore = before.toString();
            // Persist payout intent; an interrupted transfer MUST NOT be auto-retried.
            r.payoutReview = true; db.save();
            if (!money.deposit(owner, r.price, before)) { warn("payout:" + r.id, new Exception("Unconfirmed deposit")); msg(owner, "Payout needs admin review. Do not retry."); return false; }
            r.payoutClaimed = true; db.save(); // Keep payoutReview=true as a durable audit marker.
            msg(owner, "Claimed " + money(r.price) + " for " + r.species + "."); return true;
        } catch (Exception e) { warn("payout:" + r.id, e); msg(owner, "Payout paused for admin review."); return false; }
    }
    public void claimAll(ServerPlayer owner) {
        int count = 0;
        for (RentalListing r : byOwner(owner.getUUID())) if (claimEarnings(owner,r)) count++;
        if (count == 0) msg(owner, "No earnings available to claim.");
    }
    public long remaining(RentalListing r) { return Math.max(0, r.expiresAt - System.currentTimeMillis()); }
}
