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
import java.math.BigDecimal;
import net.fabricmc.loader.api.FabricLoader;
import java.util.*;
import java.util.stream.Collectors;

/** Single-threaded, server-authoritative rental coordinator; use only on server thread. */
public final class RentalEngine {
    private final MinecraftServer server;
    private final RentalRepository db;
    private final RentalConfig config;
    private final CobbleDollarsEconomy cobbleDollars = new CobbleDollarsEconomy();
    private final Map<String, RentalEconomy> impactorEconomies = new HashMap<>();
    private RentalEconomy provider(String type, String currency) throws Exception {
        String id = type == null ? "cobbledollars" : type;
        return switch (id) {
            case "cobbledollars" -> {
                if (!FabricLoader.getInstance().isModLoaded("cobbledollars"))
                    throw new IllegalStateException("CobbleDollars is not installed");
                yield cobbleDollars;
            }
            case "impactor" -> {
                if (!FabricLoader.getInstance().isModLoaded("impactor"))
                    throw new IllegalStateException("Impactor is not installed");
                yield impactorEconomies.computeIfAbsent(currency == null ? "primary" : currency, ImpactorEconomy::new);
            }
            default -> throw new IllegalStateException("Unknown economy backend: " + id);
        };
    }
    private RentalEconomy selectedProvider() throws Exception { return provider(config.economyProvider, config.impactorCurrency); }
    private RentalEconomy provider(RentalListing listing) throws Exception {
        return provider(listing.economyProvider, listing.economyCurrency);
    }
    public String selectedCurrencySymbol() { return config.currencySymbol; }
    public static String symbol(RentalListing r) { return r.currencySymbol == null ? "$" : r.currencySymbol; }
    /** Display the currency name, not the backend name. Uses the immutable currency key of each listing. */
    public static String currencyName(RentalListing r) {
        if (r.economyProvider == null || "cobbledollars".equals(r.economyProvider)) return "CobbleDollars";
        String key = r.economyCurrency;
        if (key == null || key.isBlank()) return "Impactor currency";
        int separator = key.lastIndexOf(':');
        String name = key.substring(separator + 1);
        return name.isBlank() ? key : name.replace('_', ' ').replace('-', ' ');
    }
    public void validateEconomy() throws Exception { selectedProvider().validate(); }
    private static String formatMoney(String symbol, long amount) {
        return symbol + String.format(Locale.US, "%,d", amount);
    }
    public void prefetchEconomy(UUID user) {
        try {
            selectedProvider().prefetch(user);
            // Existing contracts can use a different backend than the current default.
            for (RentalListing r : db.all())
                if (user.equals(r.ownerId) || user.equals(r.renterId)) provider(r).prefetch(user);
        } catch (Exception e) { PokemonRentalMod.LOGGER.warn("Economy prefetch failed for {}: {}", user, e.toString()); }
    }
    private final Set<String> warned = new HashSet<>();

    public RentalEngine(MinecraftServer server, RentalRepository db, RentalConfig config) { this.server = server; this.db = db; this.config = config; }
    public RentalConfig config() { return config; }
    public RentalRepository database() { return db; }
    private RegistryAccess registry() { return server.registryAccess(); }
    public Pokemon partyPokemon(ServerPlayer player, int slot) { return Cobblemon.INSTANCE.getStorage().getParty(player).get(slot - 1); }
    public List<RentalListing> marketplace() { return db.all().stream().filter(RentalListing::available).sorted(Comparator.comparing(x -> x.id)).collect(Collectors.toList()); }
    public List<RentalListing> byOwner(UUID id) { return db.all().stream().filter(x -> x.ownerId.equals(id) && (!x.originalReturned || (x.renterId != null && !x.payoutClaimed))).sorted(Comparator.comparing((RentalListing x) -> x.id).reversed()).collect(Collectors.toList()); }
    public List<RentalListing> byRenter(UUID id) { return db.all().stream().filter(x -> id.equals(x.renterId) && x.active()).collect(Collectors.toList()); }
    /** Finished rentals remain hidden; only unclaimed item deposits appear in the recovery vault. */
    public List<RentalListing> recoverableItems(UUID user) {
        return db.all().stream().filter(r -> user.equals(r.renterId) && !r.renterItemsClaimed &&
            (r.state == RentalListing.State.EXPIRED || r.state == RentalListing.State.RETURNED) &&
            db.hasRenterItems(r)).sorted(Comparator.comparing((RentalListing r) -> r.expiresAt).reversed()).toList();
    }
    public boolean isRentalPokemon(Pokemon p) { return db.wasRental(p.getUuid()); }
    /** Reject expired rental entities if chunks load AFTER their lease was cleaned up. */
    public boolean shouldDiscardTemporaryEntity(UUID pokemonId) {
        if (!db.wasRental(pokemonId)) return false;
        for (RentalListing r : db.all()) {
            if (pokemonId.equals(r.temporaryPokemonId) && r.active()) return false;
        }
        return true;
    }


    private void msg(ServerPlayer player, String text) { player.sendSystemMessage(Component.literal("§b[Pokémon Rental] §f" + text)); }
    private void warn(String key, Exception e) { if (warned.add(key)) PokemonRentalMod.LOGGER.error("Rental requires admin review: " + key, e); }

    public boolean list(ServerPlayer owner, int slot, long price, int minutes) {
        if (slot < 1 || slot > 6 || price < 1 || price > config.maxListingPrice || minutes < config.minRentalMinutes || minutes > config.maxRentalMinutes) return false;
        Pokemon original = partyPokemon(owner, slot);
        if (original == null || db.wasRental(original.getUuid())) { msg(owner, "No eligible Pokémon in that slot."); return false; }
        if (db.all().stream().anyMatch(r -> r.originalPokemonId.equals(original.getUuid()) && !r.originalReturned)) {
            msg(owner, "That Pokémon is already in rental escrow."); return false;
        }
        RentalEconomy economy;
        String lockedCurrency;
        try {
            economy = selectedProvider();
            economy.validate();
            lockedCurrency = economy.currencyKey();
        } catch (Exception e) {
            msg(owner, "Selected economy is unavailable: " + e.getMessage());
            return false;
        }
        RentalListing listing = new RentalListing();
        listing.id = UUID.randomUUID().toString(); listing.ownerId = owner.getUUID(); listing.originalPokemonId = original.getUuid();
        listing.species = original.getSpecies().getName(); listing.level = original.getLevel(); listing.price = price;
        listing.minutes = minutes; listing.state = RentalListing.State.PREPARING;
        listing.economyProvider = economy.id(); listing.economyCurrency = lockedCurrency;
        listing.currencySymbol = config.currencySymbol;
        try {
            original.recall();
            db.storeOriginal(listing, original, registry()); // durable FIRST
            db.add(listing); // recovery knows the snapshot exists BEFORE removing from Cobblemon storage
            PlayerPartyStore store = Cobblemon.INSTANCE.getStorage().getParty(owner);
            if (!store.remove(original)) { msg(owner, "Couldn't remove the Pokémon from its original slot. Escrow is pending reconciliation."); return false; }
            listing.state = RentalListing.State.LISTED;
            db.save();
            msg(owner, "Listed " + listing.species + " for " + formatMoney(symbol(listing), price) + " " + currencyName(listing) + " / " + minutes + " minutes.");
            return true;
        } catch (Exception e) { warn("list:" + listing.id, e); msg(owner, "Listing paused for safety. Contact an administrator."); return false; }
    }

    public boolean rent(ServerPlayer buyer, String id) {
        RentalListing r = db.find(id);
        if (r == null || !r.available() || r.owns(buyer.getUUID())) return false;
        if (!hasSpace(buyer.getUUID())) { msg(buyer, "Make room in your party or PC first."); return false; }
        RentalEconomy economy;
        BigDecimal before;
        try { economy = provider(r); before = economy.balance(buyer); }
        catch (ImpactorEconomy.EconomyLoadingException e) {
            msg(buyer, "Impactor account is loading. Please click Rent again shortly."); return false;
        }
        catch (Exception e) { warn("economy-read:" + r.id, e); msg(buyer, "Listing economy is unavailable; purchase cancelled."); return false; }
        if (before.compareTo(BigDecimal.valueOf(r.price)) < 0) { msg(buyer, "Not enough funds in " + currencyName(r) + "."); return false; }
        // The serialized PAYMENT_PENDING record forms the durable write-ahead journal.
        // A crash/exception after this point is NOT automatically retried: admin must reconcile money.
        r.state = RentalListing.State.PAYMENT_PENDING; r.renterId = buyer.getUUID();
        r.temporaryPokemonId = UUID.randomUUID(); r.debitBalanceBefore = before.toString();
        db.recordCopy(r.temporaryPokemonId);
        boolean paid;
        try { paid = economy.withdraw(buyer, r.price, before); }
        catch (Exception e) { r.state = RentalListing.State.PAYMENT_REVIEW; db.save(); warn("charge:" + r.id, e); msg(buyer, "Payment needs admin review. Do not retry."); return false; }
        if (!paid) {
            // If balance changed, consider it uncertain and do not retry or grant a copy.
            try {
                if (economy.balance(buyer).compareTo(before) != 0) {
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
        db.save(); // Activated only after the selected backend verifies the deduction.
        if (!giveCopy(buyer, r)) msg(buyer, "Rent paid, but delivery is pending. Free a slot and run /pokerental again.");
        else msg(buyer, "Rental active! Expires " + new Date(r.expiresAt) + ". No held items are transferred.");
        ServerPlayer owner = server.getPlayerList().getPlayer(r.ownerId);
        if (owner != null) msg(owner, r.species + " was rented for " + formatMoney(symbol(r), r.price) + " " + currencyName(r) + ". Earnings are claimable.");
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
        prefetchEconomy(player.getUUID());
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
    /** Reads the copy's last equipped held/cosmetic items before removing it.
     * An existing escrow is authoritative if a crash occurred midway through expiry.
     * If divergent items are detected we stop and require admin reconciliation.
     */
    private void cleanupRentalWithItemEscrow(RentalListing r) throws IOException {
        if (r.renterId == null || r.temporaryPokemonId == null) throw new IOException("Missing rental ownership metadata");
        Set<UUID> players = new HashSet<>();
        players.add(r.renterId);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) players.add(p.getUUID());

        RentalRepository.RenterItems candidate = null;
        for (UUID user : players) {
            PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(user, registry());
            PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(user, registry());
            for (Pokemon pokemon : new Pokemon[]{party.get(r.temporaryPokemonId), pc.get(r.temporaryPokemonId)}) {
                if (pokemon == null) continue;
                RentalRepository.RenterItems equipped = new RentalRepository.RenterItems(pokemon.heldItem(), pokemon.cosmeticItem());
                if (equipped.empty()) continue;
                if (candidate != null && !RentalRepository.sameItems(candidate,equipped))
                    throw new IOException("Conflicting items on copies of rental " + r.id);
                candidate = equipped;
            }
        }
        // Never replace an earlier write-ahead snapshot with a later empty/different item state.
        if (db.hasRenterItems(r)) {
            var saved=db.readRenterItems(r,registry());
            if (candidate != null && !RentalRepository.sameItems(saved,candidate))
                throw new IOException("Escrow differs from still-equipped copy for rental " + r.id);
        } else if (candidate != null) {
            // Items must be safe on disk before the temporary Pokémon is removed.
            db.storeRenterItems(r,candidate,registry());
        }
        for (UUID user : players) {
            strip(user,r.temporaryPokemonId);
            if (hasPokemon(user,r.temporaryPokemonId)) throw new IOException("Unable to remove rental copy from storage " + user);
        }
    }
    /** Offline PC/party are scanned too; pasture or external trade plugins still need integration testing. */
    public boolean expire(RentalListing r) {
        if (r.state != RentalListing.State.ACTIVE || System.currentTimeMillis() < r.expiresAt) return false;
        try {
            cleanupRentalWithItemEscrow(r);
            r.state = RentalListing.State.EXPIRED; db.save();
            ServerPlayer owner = server.getPlayerList().getPlayer(r.ownerId);
            if (owner != null) msg(owner, r.species + " rental ended. Claim your original in /pokerental.");
            ServerPlayer renter = server.getPlayerList().getPlayer(r.renterId);
            if (renter != null && db.hasRenterItems(r))
                msg(renter, "Your rental ended. Recover equipped items from /pokerental -> Recover Items.");
            return true;
        } catch (Exception e) { warn("expiry:" + r.id, e); return false; }
    }
    /** Give saved items only to the renter, and only once. Crash-ambiguous delivery stays locked. */
    public boolean claimRenterItems(ServerPlayer renter, String id) {
        RentalListing r=db.find(id);
        if (r == null || r.renterId == null || !r.renterId.equals(renter.getUUID()) ||
            r.renterItemsClaimed || r.renterItemClaimPending ||
            (r.state != RentalListing.State.EXPIRED && r.state != RentalListing.State.RETURNED) ||
            !db.hasRenterItems(r)) return false;
        try {
            var items=db.readRenterItems(r,registry());
            int available=0;
            // Conservative: reserve one *empty main inventory slot* per escrow item.
            for (int i=0;i<36;i++) if (renter.getInventory().getItem(i).isEmpty()) available++;
            if (available < items.neededSlots()) {
                msg(renter,"Please make " + items.neededSlots() + " empty inventory slot(s) before claiming.");
                return false;
            }
            r.renterItemClaimPending=true; db.save(); // Durable no-retry marker BEFORE mutation.
            boolean success=true;
            if (!items.held().isEmpty()) success=renter.getInventory().add(items.held().copy());
            if (success && !items.cosmetic().isEmpty()) success=renter.getInventory().add(items.cosmetic().copy());
            if (!success) throw new IOException("Inventory add was not confirmed");
            r.renterItemsClaimed=true; db.save();
            msg(renter,"Rental equipment successfully returned to your inventory.");
            return true;
        } catch (Exception e) {
            warn("item-claim:" + id,e);
            msg(renter,"Item recovery paused for admin review. Do not retry; escrow is preserved.");
            return false;
        }
    }
    public RentalRepository.RenterItems previewRenterItems(RentalListing r) throws IOException {
        return db.readRenterItems(r,registry());
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
            if (r.temporaryPokemonId != null) cleanupRentalWithItemEscrow(r);
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
            RentalEconomy economy = provider(r);
            BigDecimal before = economy.balance(owner);
            r.payoutBalanceBefore = before.toPlainString();
            // Persist payout intent; an interrupted transfer MUST NOT be auto-retried.
            r.payoutReview = true; db.save();
            if (!economy.deposit(owner, r.price, before)) { warn("payout:" + r.id, new Exception("Unconfirmed deposit")); msg(owner, "Payout needs admin review. Do not retry."); return false; }
            r.payoutClaimed = true; db.save(); // Keep payoutReview=true as a durable audit marker.
            msg(owner, "Claimed " + formatMoney(symbol(r), r.price) + " " + currencyName(r) + " for " + r.species + "."); return true;
        } catch (ImpactorEconomy.EconomyLoadingException e) {
            msg(owner, "Impactor account is loading. Please try claiming again shortly."); return false;
        } catch (Exception e) { warn("payout:" + r.id, e); msg(owner, "Payout paused for admin review."); return false; }
    }
    public void claimAll(ServerPlayer owner) {
        int count = 0;
        for (RentalListing r : byOwner(owner.getUUID())) if (claimEarnings(owner,r)) count++;
        if (count == 0) msg(owner, "No earnings available to claim.");
    }
    public long remaining(RentalListing r) { return Math.max(0, r.expiresAt - System.currentTimeMillis()); }
}
