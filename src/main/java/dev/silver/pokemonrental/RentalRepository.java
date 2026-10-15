package dev.silver.pokemonrental;

import com.google.gson.*;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.core.RegistryAccess;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Write-ahead record + immutable source snapshot. Crash recovery is fail-closed. */
public final class RentalRepository {
    public static final class StateData {
        int schema = 1;
        ArrayList<RentalListing> listings = new ArrayList<>();
        HashSet<UUID> usedCopyIds = new HashSet<>();
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path root, vault, index;
    private StateData data;

    public RentalRepository(Path worldRoot) throws IOException {
        root = worldRoot.resolve("pokemon-rental");
        vault = root.resolve("originals");
        index = root.resolve("index.json");
        Files.createDirectories(vault);
        if (Files.exists(index)) {
            try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
                data = GSON.fromJson(reader, StateData.class);
            }
            if (data == null || data.schema != 1 || data.listings == null || data.usedCopyIds == null)
                throw new IOException("Invalid rental index; NOT overwriting damaged data. Restore from backup.");
        } else { data = new StateData(); save(); }
    }
    public Collection<RentalListing> all() { return data.listings; }
    public RentalListing find(String id) { return data.listings.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null); }
    public boolean wasRental(UUID pokemon) { return data.usedCopyIds.contains(pokemon); }
    public void recordCopy(UUID pokemon) { data.usedCopyIds.add(pokemon); save(); }
    public void add(RentalListing record) { data.listings.add(record); save(); }

    /** Original stays stored intact; every display/copy is loaded from this snapshot. */
    public void storeOriginal(RentalListing record, Pokemon pokemon, RegistryAccess registries) throws IOException {
        Path file = vault.resolve(record.id + ".json");
        if (Files.exists(file)) throw new IOException("Escrow snapshot already exists");
        writeAtomically(file, pokemon.saveToJSON(registries, new JsonObject()).toString());
    }
    public Pokemon original(RentalListing record, RegistryAccess registries) throws IOException {
        Path file = vault.resolve(record.id + ".json");
        if (!Files.exists(file)) throw new IOException("Missing escrow for " + record.id);
        JsonObject original;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { original = JsonParser.parseReader(reader).getAsJsonObject(); }
        return Pokemon.Companion.loadFromJSON(registries, original);
    }
    public Pokemon rentalCopy(RentalListing record, RegistryAccess registries) throws IOException {
        Path file = vault.resolve(record.id + ".json");
        JsonObject j;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { j = JsonParser.parseReader(reader).getAsJsonObject(); }
        // Drop held/cosmetic items, shoulder state and pasture tether BEFORE loading.
        j.remove(com.cobblemon.mod.common.util.DataKeys.HELD_ITEM);
        j.remove(com.cobblemon.mod.common.util.DataKeys.POKEMON_COSMETIC_ITEM);
        j.remove(com.cobblemon.mod.common.util.DataKeys.POKEMON_STATE);
        j.remove(com.cobblemon.mod.common.util.DataKeys.TETHERING_ID);
        Pokemon copy = Pokemon.Companion.loadFromJSON(registries, j);
        copy.setUuid(record.temporaryPokemonId);
        copy.setTradeable(false);
        return copy;
    }
    public void save() {
        try { writeAtomically(index, GSON.toJson(data)); }
        catch (IOException e) { throw new IllegalStateException("Rental index not saved: HALTING transaction", e); }
    }
    private static void writeAtomically(Path path, String text) throws IOException {
        Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temp.toFile())) {
            stream.write(text.getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
        }
        try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
