package dev.silver.pokemonrental;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Server-only settings. The generated .json file deliberately supports JSONC-style comments. */
public final class RentalConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CURRENT_FILE = "cobblemon-rental.json";
    private static final String LEGACY_FILE = "pokemon-rental.json";

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
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path current = configDirectory.resolve(CURRENT_FILE);
        Path legacy = configDirectory.resolve(LEGACY_FILE);

        // Existing new configuration always takes precedence. Never silently overwrite it.
        if (Files.exists(current)) return read(current);

        // Upgrade existing servers without resetting any chosen economy, currency or limits.
        RentalConfig config = Files.exists(legacy) ? read(legacy) : new RentalConfig();
        config.validate();
        Files.createDirectories(configDirectory);
        writeNewFile(current, annotatedJson(config));
        if (Files.exists(legacy)) {
            PokemonRentalMod.LOGGER.info("Migrated configuration from {} to {}. The old file was left untouched as a backup.",
                    LEGACY_FILE, CURRENT_FILE);
        }
        return config;
    }

    private static RentalConfig read(Path path) throws IOException {
        RentalConfig config;
        // Gson's lenient reader supports // and /* ... */ comments in this .json file.
        try (Reader file = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             JsonReader json = new JsonReader(file)) {
            json.setLenient(true);
            config = GSON.fromJson(json, RentalConfig.class);
        } catch (RuntimeException e) {
            throw new IOException("Invalid " + path.getFileName() + "; fix the file instead of deleting rental data", e);
        }
        if (config == null) throw new IOException(path.getFileName() + " cannot be null");
        config.validate();
        return config;
    }

    /** The file has an ordinary .json extension, but the mod explicitly permits JSONC comments. */
    private static String annotatedJson(RentalConfig config) {
        return """
                // Cobblemon Rental - Server Configuration
                // Comments in this file are supported by Cobblemon Rental.
                // Restart the server to apply changes. Existing contracts are not converted.
                // Keep your old economy installed until all its outstanding payouts are claimed.
                {
                  // Economy used for NEW listings: "cobbledollars" (default) or "impactor".
                  // Only the selected economy mod is required for new rentals.
                  "economyProvider": %s,

                  // Impactor only: "primary" uses Impactor's default currency.
                  // Or set a registered namespaced currency, e.g. "example:coins".
                  // Ignored when economyProvider is "cobbledollars".
                  "impactorCurrency": %s,

                  // Currency prefix shown in rental menus, e.g. "$" or "Coins ".
                  // This changes display text only; it does not convert account balances.
                  "currencySymbol": %s,

                  // Maximum price a player may set for a new rental (default: 100,000,000).
                  // Minimum permitted listing price is 1.
                  "maxListingPrice": %d,

                  // Shortest allowed rental duration, expressed in MINUTES.
                  // Default 120 = 2 hours. Set 1 to allow one-minute rentals.
                  "minRentalMinutes": %d,

                  // Longest allowed rental duration, expressed in MINUTES.
                  // Default 1440 = 24 hours. Examples: 2880 = 48 hours,
                  // 525600 = one 365-day year. Must be >= minRentalMinutes.
                  // WARNING: More than 24 hours is NOT recommended; players
                  // may accidentally lock away their Pokemon for a very long time.
                  "maxRentalMinutes": %d
                }
                """.formatted(
                GSON.toJson(config.economyProvider),
                GSON.toJson(config.impactorCurrency),
                GSON.toJson(config.currencySymbol),
                config.maxListingPrice,
                config.minRentalMinutes,
                config.maxRentalMinutes
        );
    }

    private static void writeNewFile(Path destination, String contents) throws IOException {
        // Write to a unique temporary file and move into place. Never replace an admin file.
        Path temp = Files.createTempFile(destination.getParent(), "cobblemon-rental-", ".tmp");
        try {
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
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
