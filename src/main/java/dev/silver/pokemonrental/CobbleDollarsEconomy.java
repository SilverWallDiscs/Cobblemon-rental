package dev.silver.pokemonrental;

import net.minecraft.server.level.ServerPlayer;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.math.BigDecimal;

/** Supports CobbleDollars' 2.x player extension methods; fails closed if the API changes. */
public final class CobbleDollarsEconomy implements RentalEconomy {
    @Override public String id() { return "cobbledollars"; }
    @Override public String currencyKey() { return "cobbledollars"; }
    @Override public void validate() throws Exception { checkApi(); }
    private Method getter, setter;
    public void checkApi() throws Exception {
        Class<?> extension = Class.forName("fr.harmex.cobbledollars.common.utils.CobbleDollarsPlayer");
        getter = extension.getMethod("cobbleDollars$getCobbleDollars");
        setter = extension.getMethod("cobbleDollars$setCobbleDollars", BigInteger.class);
    }
    private BigInteger integerBalance(ServerPlayer player) throws Exception {
        if (getter == null) checkApi();
        Object result = getter.invoke(player);
        if (!(result instanceof BigInteger n)) throw new IllegalStateException("Unrecognized CobbleDollars balance type");
        return n;
    }
    @Override public BigDecimal balance(ServerPlayer player) throws Exception { return new BigDecimal(integerBalance(player)); }
    @Override public boolean withdraw(ServerPlayer player, long amount, BigDecimal exactPreviousBalance) throws Exception {
        BigInteger before = exactPreviousBalance.toBigIntegerExact();
        if (amount <= 0) return false;
        BigInteger actual = integerBalance(player);
        if (!actual.equals(before) || actual.compareTo(BigInteger.valueOf(amount)) < 0) return false;
        setter.invoke(player, actual.subtract(BigInteger.valueOf(amount)));
        return integerBalance(player).equals(actual.subtract(BigInteger.valueOf(amount)));
    }
    @Override public boolean deposit(ServerPlayer player, long amount, BigDecimal exactPreviousBalance) throws Exception {
        BigInteger before = exactPreviousBalance.toBigIntegerExact();
        if (amount <= 0 || !integerBalance(player).equals(before)) return false;
        setter.invoke(player, before.add(BigInteger.valueOf(amount)));
        return integerBalance(player).equals(before.add(BigInteger.valueOf(amount)));
    }
}
