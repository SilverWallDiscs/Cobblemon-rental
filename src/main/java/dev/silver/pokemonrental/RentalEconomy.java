package dev.silver.pokemonrental;

import net.minecraft.server.level.ServerPlayer;
import java.math.BigDecimal;
import java.util.UUID;

/** Optional currency backends; all money-changing actions run on the Minecraft server thread. */
public interface RentalEconomy {
    String id();
    String currencyKey();
    void validate() throws Exception;
    default void prefetch(UUID player) throws Exception { }
    BigDecimal balance(ServerPlayer player) throws Exception;
    boolean withdraw(ServerPlayer player, long amount, BigDecimal exactPreviousBalance) throws Exception;
    boolean deposit(ServerPlayer player, long amount, BigDecimal exactPreviousBalance) throws Exception;
}
