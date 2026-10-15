# Pokémon Rental (Fabric 1.21.1) — source prototype v0.1.3

A **server-only** Cobblemon 1.7.3 Pokémon rental marketplace using **CobbleDollars 2.0 Beta 6.1**. Clients need their normal Cobblemon client but do not need this addon.

**CURRENT STATUS: GITHUB ACTIONS BUILD PASSED; GAMEPLAY/ANTI-DUPLICATION NOT VERIFIED. NOT SAFE FOR A PUBLIC ECONOMY.** The GitHub Actions build compiles and remaps the server-side JAR, but no Minecraft server runtime tests have been performed. Test only on a disposable private server with backups.

## Features implemented in the source

- `/pokerental` opens a vanilla 54-slot, 45-listings-per-page marketplace using **`cobblemon:pokemon_model`**.
- Hover for level, nature, ability, gender, egg groups, IVs, EVs, price and rental length.
- `/pokerental 1` to `/pokerental 6`: choose a party Pokémon, configure the rental price (displayed with `$`) and rental duration in the GUI. Left-click to adjust amounts; right-click buttons for 10× steps. Minimum and maximum duration buttons support very long rentals, subject to the server configuration.
- `/pokerental mine`: manage current listings, claim outstanding earnings, and reclaim originals after expiry or cancel listings before purchase. Entries disappear from the menu after the original was returned **and** any earnings were claimed; internal audit records remain preserved.
- `/pokerental rented`: **only current, unexpired rentals** and their remaining time. Finished rentals are hidden from player menus.
- An original-Pokémon snapshot is written to `world/pokemon-rental/originals/*.json` before removing it from the owner's party. An immutable copy is created with a **different Pokémon UUID**, without held item, cosmetic item, shoulder state, or pasture tether. The original is not changed by the renter.
- Records persist in `world/pokemon-rental/index.json` via temp-file + sync + atomic rename.
- When a rental expires, attempt to remove the temporary UUID from the renter's party/PC (including offline data), scan online players, recall the Pokémon entity, discard expired temporary Pokémon entities on chunk load, and allow the owner to claim the untouched original.
- Disabled click types in a *server-only chest menu*; no draggable GUI items / creative clone via those GUI controls. Tracks temporary Pokémon UUIDs permanently as a blocklist for re-listing.
- CobbleDollars balances through reflective BigInteger 2.x player interface; payment and payout intents are written before money changes. If a crash leaves the payment ambiguous, the record moves (or remains) in a REVIEW/PENDING state for an administrator; it must not be automatically retried.

## Requirements / compile

- Java 21, Gradle 8.12.1 (install separately; Gradle Wrapper JAR is not bundled; CI installs Gradle 8.12.1).
- Minecraft Fabric 1.21.1, Fabric API, Fabric Loader, Cobblemon **1.7.3** and CobbleDollars **2.0.0 Beta-6.1**.
- `gradle build` (source uses Fabric Loom and Mojang official mappings, no client code). Output should be `build/libs/pokemon-rental-0.1.3.jar` after a successful build.
- Install the compiled JAR only in the server's `mods/` folder.


## Server configuration (English)

On the first startup, the mod creates **`config/pokemon-rental.json`** in the Minecraft server directory:

```json
{
  "maxListingPrice": 100000000,
  "minRentalMinutes": 120,
  "maxRentalMinutes": 1440
}
```

- `maxListingPrice` sets the maximum rental listing price in CobbleDollars. Default **$100,000,000**; minimum allowed value: 1.
- `minRentalMinutes` sets the minimum duration in whole minutes. Default **120 minutes (2 hours)**. Set to **1** to allow one-minute rentals.
- `maxRentalMinutes` sets the maximum duration in whole minutes. Default **1440 minutes (24 hours)**. Set to **2880** for 48 hours or **525600** for one 365-day year. Longer durations are also supported (up to Java's positive 32-bit integer range), but are strongly discouraged.
- The maximum must be at least the configured minimum. Invalid configuration fails closed and is reported in the server logs; the mod does not overwrite the file.
- **Restart the server after changing the configuration.** Changes affect newly created listings, not existing rental contracts.

**Recommendation:** Avoid setting a maximum greater than 24 hours, because a player may accidentally lock their Pokémon for an excessively long time. A rental's duration cannot be changed after purchase.

## Menu interaction and rental history

The mod opens one vanilla 6-row chest menu per session, then updates its slot contents rather than opening a new container on every click. This prevents the mouse from resetting to the center when adjusting the price, duration, or changing pages.

All 54 GUI slots remain **server-owned and non-extractable**; clicks are intercepted, and shift-click, keyboard hotbar swaps, creative middle-click copies, dragging, and dropping are denied. Player inventory slots are included in the client menu but locked while the menu is open.

**History is hidden, not erased:** once the Pokémon has been returned and outstanding earnings have been claimed, the corresponding record no longer appears in `My Listings`. `My Rentals` shows active rentals only. Permanent temporary-Pokémon UUID records and original escrow snapshots are retained for crash recovery and anti-duplication checks. Never delete them manually.

## Important remaining work — mandatory before public deployment

1. **Compilation and runtime compatibility tests** against the exact server pack. A compilable test JAR is supplied in GitHub Actions artifacts; server testing has NOT been performed.
2. **Cross-account/offline transfer hardening:** `tradeable = false` prevents normal trades, but external GTS, commands, breeding, custom pasture implementations, and other mods may transfer, clone or transform rented Pokémon. To guarantee no duplication, add Cobblemon storage hooks/mixins and a comprehensive ownership audit, including *every* party and PC and unloaded tethered entities, before claiming absolute safety. The current online sweep alone is insufficient.
3. **Crash-consistent transaction integration:** direct BigInteger set/get is *not* a multi-resource ACID transaction. REVIEW/PENDING statuses must be manually reconciled with CobbleDollars ledger/account balance and escrow before re-enabling payments. Also handle interrupted payouts with admin tools and log audit details.
4. **World durability:** force-save Cobblemon party/PC writes and integrate with its storage factory save lifecycle; test crash after every persisted transition. Test the atomic-rename filesystem behavior and backups.
5. **Battle/item/pasture edge cases:** test an expiry during combat, Pokémon sent out or riding, owner/renter offline, holding an item added by the renter, external inventory transactions, and PC full. Decide whether/where to return rental-period held items. Currently the snapshot guarantees preservation only of the **owner's** original held item. Any new items attached to the temporary copy at expiry may be lost.
6. **Permissions and policy:** configure listing limits, prices, tax, blacklists (e.g. legendaries), cooldowns, abuse monitoring, admin repair commands, multiple languages and UX improvements.

### Operator warnings

- **NEVER** delete files under `world/pokemon-rental/originals`; they are the source of truth for owners' Pokémon.
- Back up **both** the Cobblemon player storage and `world/pokemon-rental` **together** before testing.
- Do not edit the escrow JSON and player stores independently while server is running.
- For `PAYMENT_PENDING`, `PAYMENT_REVIEW`, or `payoutReview=true`, freeze/reconcile transactions before attempting any recovery; do not simply switch statuses by hand.

### Implementation references

- [Cobblemon 1.7.3 source API](https://github.com/MayIHaveK/cobblemon-1.7.3)
- [Cobblemon 1.7.3 Fabric](https://modrinth.com/mod/cobblemon/version/kF7CvxTo)
- [CobbleDollars Beta 6.1 Fabric](https://modrinth.com/mod/cobbledollars/version/NQdxKsW7)
- [Example CobbleDollars reflective BigInteger integration](https://github.com/IAmXenox/cobbledollars-safari-addon)

## GitHub Actions build

The `.github/workflows/build.yml` workflow installs Java 21 and Gradle 8.12.1, compiles with `gradle clean build`, and uploads the remapped JAR as a downloadable workflow artifact. **Build #4 passed** for v0.1.2; newer versions use the same CI workflow; the JAR can be downloaded from the Actions artifact named `pokemon-rental-jar`. A successful compilation does NOT imply runtime anti-duplication safety.

### Gradle setup note

Fabric Loom **1.10.5** requires **Gradle 8.12+**. An earlier source prototype incorrectly used Gradle 8.10.2, which cannot load Loom 1.10.x. The workflow was corrected to use Gradle 8.12.1. This repository deliberately does not commit a non-working Gradle Wrapper without its binary `gradle-wrapper.jar`: install Gradle 8.12.1 to build locally, or download the compiled artifact from GitHub Actions once the workflow passes.

### Initial private-server test checklist

1. Start Fabric 1.21.1 with Fabric API, Cobblemon 1.7.3, CobbleDollars 2.0 Beta 6.1 and `pokemon-rental-0.1.3.jar` on the **server only**.
2. Back up world data. With two test accounts, list a Pokémon carrying a held item using `/pokerental 1`, verify the original disappears from the owner's party, and verify the marketplace GUI.
3. Rent it with the second account; verify one CobbleDollars debit, a temporary Pokémon with no held item, and the owner payout claim. Test relogging and both party/PC placement.
4. Test after the 2-hour minimum rental and server restarts; check that the rental copy is removed and the untouched original can be reclaimed with its original held item.
5. Repeat with PC, pasture and offline players. Record any duplication, stale entity, or vanished held-item behavior. **Never run this prototype on a public server economy.**
