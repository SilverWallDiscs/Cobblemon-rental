package dev.silver.pokemonrental;

import net.minecraft.server.level.ServerPlayer;
import java.lang.reflect.Method;
import java.math.BigInteger;

/** Supports CobbleDollars' 2.x player extension methods; fails closed if the API changes. */
public final class CobbleDollarsEconomy {
    private Method getter, setter;
    public void checkApi() throws Exception {
        Class<?> extension = Class.forName("fr.harmex.cobbledollars.common.utils.CobbleDollarsPlayer");
        getter = extension.getMethod("cobbleDollars$getCobbleDollars");
        setter = extension.getMethod("cobbleDollars$setCobbleDollars", BigInteger.class);
    }
    public BigInteger balance(ServerPlayer player) throws Exception {
        if (getter == null) checkApi();
        Object result = getter.invoke(player);
        if (!(result instanceof BigInteger n)) throw new IllegalStateException("Unrecognized CobbleDollars balance type");
        return n;
    }
    public boolean withdraw(ServerPlayer player, long amount, BigInteger exactPreviousBalance) throws Exception {
        if (amount <= 0) return false;
        BigInteger actual = balance(player);
        if (!actual.equals(exactPreviousBalance) || actual.compareTo(BigInteger.valueOf(amount)) < 0) return false;
        setter.invoke(player, actual.subtract(BigInteger.valueOf(amount)));
        return balance(player).equals(actual.subtract(BigInteger.valueOf(amount)));
    }
    public boolean deposit(ServerPlayer player, long amount, BigInteger exactPreviousBalance) throws Exception {
        if (amount <= 0 || !balance(player).equals(exactPreviousBalance)) return false;
        setter.invoke(player, exactPreviousBalance.add(BigInteger.valueOf(amount)));
        return balance(player).equals(exactPreviousBalance.add(BigInteger.valueOf(amount)));
    }
}
