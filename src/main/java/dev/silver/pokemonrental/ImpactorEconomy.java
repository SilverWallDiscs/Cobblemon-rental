package dev.silver.pokemonrental;

import net.impactdev.impactor.api.economy.EconomyService;
import net.impactdev.impactor.api.economy.accounts.Account;
import net.impactdev.impactor.api.economy.currency.Currency;
import net.kyori.adventure.key.Key;
import net.minecraft.server.level.ServerPlayer;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Impactor 5.3.x: account acquisition is asynchronous; never block the Minecraft tick thread. */
public final class ImpactorEconomy implements RentalEconomy {
    private final String requestedCurrency;
    private final ConcurrentHashMap<UUID, CompletableFuture<Account>> accounts = new ConcurrentHashMap<>();

    public ImpactorEconomy(String requestedCurrency) {
        this.requestedCurrency = requestedCurrency;
    }
    @Override public String id() { return "impactor"; }
    private EconomyService service() { return EconomyService.instance(); }
    private Currency currency() {
        EconomyService economy = service();
        if (requestedCurrency == null || requestedCurrency.equalsIgnoreCase("primary"))
            return economy.currencies().primary();
        return economy.currencies().currency(Key.key(requestedCurrency))
                .orElseThrow(() -> new IllegalStateException("Unknown Impactor currency: " + requestedCurrency));
    }
    @Override public String currencyKey() { return currency().key().asString(); }
    @Override public void validate() { currency(); }

    @Override public void prefetch(UUID player) {
        // Impactor may load accounts from a disk/database asynchronously.
        // The cache holds the future, not a synchronous query on the server thread.
        accounts.computeIfAbsent(player, id -> service().account(currency(), id));
    }
    private Account account(UUID id) throws Exception {
        prefetch(id);
        CompletableFuture<Account> pending = accounts.get(id);
        if (!pending.isDone()) throw new EconomyLoadingException();
        try {
            Account account = pending.join(); // isDone() guarantees this does not wait for I/O
            if (account == null) throw new IllegalStateException("Impactor returned a null account");
            return account;
        } catch (CompletionException e) {
            accounts.remove(id, pending); // subsequent interaction may retry a failed account load
            throw new IllegalStateException("Impactor could not load the economy account", e.getCause());
        }
    }
    @Override public BigDecimal balance(ServerPlayer player) throws Exception {
        return account(player.getUUID()).balance();
    }
    @Override public boolean withdraw(ServerPlayer player, long amount, BigDecimal before) throws Exception {
        if (amount <= 0) return false;
        Account account = account(player.getUUID());
        if (account.balance().compareTo(before) != 0 || before.compareTo(BigDecimal.valueOf(amount)) < 0) return false;
        boolean success = account.withdraw(BigDecimal.valueOf(amount)).successful();
        BigDecimal after = account.balance();
        if (success && after.compareTo(before.subtract(BigDecimal.valueOf(amount))) != 0)
            throw new IllegalStateException("Impactor withdrawal result/balance mismatch; admin review required");
        return success;
    }
    @Override public boolean deposit(ServerPlayer player, long amount, BigDecimal before) throws Exception {
        if (amount <= 0) return false;
        Account account = account(player.getUUID());
        if (account.balance().compareTo(before) != 0) return false;
        boolean success = account.deposit(BigDecimal.valueOf(amount)).successful();
        BigDecimal after = account.balance();
        if (success && after.compareTo(before.add(BigDecimal.valueOf(amount))) != 0)
            throw new IllegalStateException("Impactor deposit result/balance mismatch; admin review required");
        return success;
    }
    public static final class EconomyLoadingException extends Exception {
        public EconomyLoadingException() { super("Impactor account is loading, please try again shortly"); }
    }
}
