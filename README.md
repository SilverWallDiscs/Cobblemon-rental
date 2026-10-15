# Pokémon Rental (Fabric 1.21.1) — source prototype v0.1.2

A **server-only** Cobblemon 1.7.3 Pokémon rental marketplace using **CobbleDollars 2.0 Beta 6.1**. Clients need their normal Cobblemon client but do not need this addon.

**CURRENT STATUS: SOURCE PROTOTYPE, NOT VERIFIED TO COMPILE/RUN, NOT SAFE FOR A PUBLIC ECONOMY.** The development environment used to create this project cannot fetch Gradle dependencies or boot a Minecraft server. Test on an isolated server and fix integration/compiler differences before attempting public release. This is not a validated production build.

## Features implemented in the source

- `/pokerental` opens a vanilla 54-slot, 45-listings-per-page marketplace using **`cobblemon:pokemon_model`**.
- Hover for level, nature, ability, gender, egg groups, IVs, EVs, price and rental length.
- `/pokerental 1` to `/pokerental 6`: pick a party Pokémon; configure CobbleDollars price (±1, ±100, ±1000, ±10000) and duration (±1/15/60 minutes, **2–24 hours**) in a GUI.
- `/pokerental mine`: manage listings, claim earnings, and reclaim originals after expiry or cancel listings before purchase.
- `/pokerental rented`: current/past rentals and their status.
- An original-Pokémon snapshot is written to `world/pokemon-rental/originals/*.json` before removing it from the owner's party. An immutable copy is created with a **different Pokémon UUID**, without held item, cosmetic item, shoulder state, or pasture tether. The original is not changed by the renter.
- Records persist in `world/pokemon-rental/index.json` via temp-file + sync + atomic rename.
- When a rental expires, attempt to remove the temporary UUID from the renter's party/PC (including offline data), scan online players, recall the Pokémon entity, discard expired temporary Pokémon entities on chunk load, and allow the owner to claim the untouched original.
- Disabled click types in a *server-only chest menu*; no draggable GUI items / creative clone via those GUI controls. Tracks temporary Pokémon UUIDs permanently as a blocklist for re-listing.
- CobbleDollars balances through reflective BigInteger 2.x player interface; payment and payout intents are written before money changes. If a crash leaves the payment ambiguous, the record moves (or remains) in a REVIEW/PENDING state for an administrator; it must not be automatically retried.

## Requirements / compile

- Java 21, Gradle 8.12.1 (install separately; Gradle Wrapper JAR is not bundled; CI installs Gradle 8.12.1).
- Minecraft Fabric 1.21.1, Fabric API, Fabric Loader, Cobblemon **1.7.3** and CobbleDollars **2.0.0 Beta-6.1**.
- `gradle build` (source uses Fabric Loom and Mojang official mappings, no client code). Output should be `build/libs/pokemon-rental-0.1.2.jar` after a successful build.
- Install the compiled JAR only in the server's `mods/` folder.

## Important remaining work — mandatory before public deployment

1. **Compilation and runtime compatibility tests** against the exact server pack. No executable JAR is supplied because compilation and server testing were not possible here.
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

The `.github/workflows/build.yml` workflow installs Java 21 and Gradle 8.12.1, compiles with `gradle clean build`, and uploads the remapped JAR as a downloadable workflow artifact. Building in GitHub Actions does not imply that runtime anti-duplication behavior has been tested.

### Gradle setup note

Fabric Loom **1.10.5** requires **Gradle 8.12+**. An earlier source prototype incorrectly used Gradle 8.10.2, which cannot load Loom 1.10.x. The workflow was corrected to use Gradle 8.12.1. This repository deliberately does not commit a non-working Gradle Wrapper without its binary `gradle-wrapper.jar`: install Gradle 8.12.1 to build locally, or download the compiled artifact from GitHub Actions once the workflow passes.
