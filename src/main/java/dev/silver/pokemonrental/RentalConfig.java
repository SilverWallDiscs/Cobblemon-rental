package dev.silver.pokemonrental;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Server-only settings. Durations are expressed in whole minutes (1 minute to multiple years). */
public final class RentalConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Economy backend: "cobbledollars" (default) or "impactor". */
    public String economyProvider = "cobbledollars";
    /** Impactor currency: "primary" uses the server's primary currency; or use namespace:key. */
    public String impactorCurrency = "primary";
    /** Display prefix used for the chosen currency (defaults to $). */
    public String currencySymbol = "$";
    /** Maximum listing price in the chosen currency. Default: 100,000,000. */
    public long maxListingPrice = 100_000_000L;
    /** Minimum rental duration in minutes. Default: 120 (2 hours). */
    public int minRentalMinutes = 120;
    /** Maximum rental duration in minutes. Default: 1440 (24 hours). */
    public int maxRentalMinutes = 1_440;

    public static RentalConfig load() throws IOException {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("pokemon-rental.json");
        if (!Files.exists(path)) {
            RentalConfig defaults = new RentalConfig();
            defaults.validate();
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.createDirectories(path.getParent());
            Files.writeString(temp, GSON.toJson(defaults), StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path); }
            return defaults;
        }
        // Fail closed instead of silently overwriting an invalid or partial admin configuration.
        RentalConfig config;
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            config = GSON.fromJson(in, RentalConfig.class);
        } catch (RuntimeException e) { throw new IOException("Invalid pokemon-rental.json; fix the file instead of deleting rental data", e); }
        if (config == null) throw new IOException("pokemon-rental.json cannot be null");
        config.validate();
        return config;
    }

    private void validate() throws IOException {
        if (!"cobbledollars".equals(economyProvider) && !"impactor".equals(economyProvider))
            throw new IOException("economyProvider must be either cobbledollars or impactor");
        if (impactorCurrency == null || impactorCurrency.isBlank())
            throw new IOException("impactorCurrency must be 'primary' or a namespaced Impactor currency key");
        if (currencySymbol == null || currencySymbol.isBlank() || currencySymbol.length() > 16)
            throw new IOException("currencySymbol must have 1-16 characters");
        if (maxListingPrice < 1) throw new IOException("maxListingPrice must be at least 1");
        if (minRentalMinutes < 1) throw new IOException("minRentalMinutes must be at least 1 (one minute)");
        if (maxRentalMinutes < minRentalMinutes)
            throw new IOException("maxRentalMinutes must be greater than or equal to minRentalMinutes");
        if (maxRentalMinutes > 1_440)
            PokemonRentalMod.LOGGER.warn("Configured rental maximum exceeds 24 hours. Long rentals may lock a player's Pokémon for much longer than intended.");
    }

    public int initialRentalMinutes() { return Math.max(minRentalMinutes, Math.min(120, maxRentalMinutes)); }
    public long initialPrice() { return Math.min(1_000L, maxListingPrice); }
}
