package dev.silver.pokemonrental;

import java.util.UUID;

/** Gson-friendly DTO. All edits occur on Minecraft's server thread. */
public final class RentalListing {
    public enum State { PREPARING, LISTED, PAYMENT_PENDING, ACTIVE, EXPIRED, RETURNED, CLOSED, PAYMENT_REVIEW, PAYOUT_REVIEW }
    public String id;
    public UUID ownerId;
    public UUID originalPokemonId;
    public String species;
    public int level;
    public long price;
    public int minutes;
    public State state;
    public UUID renterId;
    public UUID temporaryPokemonId;
    public long startsAt;
    public long expiresAt;
    public String debitBalanceBefore;
    public String payoutBalanceBefore;
    public boolean payoutClaimed;
    public boolean payoutReview;
    public boolean originalReturned;

    public boolean available() { return state == State.LISTED; }
    public boolean active() { return state == State.ACTIVE && System.currentTimeMillis() < expiresAt; }
    public boolean owns(UUID user) { return ownerId.equals(user); }
}
