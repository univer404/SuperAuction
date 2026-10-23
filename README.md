# SuperAuction

An original Minecraft auction-house plugin project targeting Paper/Purpur 1.21.11 and Java 21.

## Build

Install JDK 21 and Maven, then run:

```sh
mvn clean verify
```

The JAR, if the build succeeds, is written to `target/`. Install Vault and a compatible economy plugin before enabling any economy-dependent features.

## Implemented in the current development snapshot

- `/ah` opens a real inventory-based listing browser.
- `/ah sell <price>` stores an item listing using YAML escrow records; accepts plain prices and k/m suffixes.
- `/ah mine` opens the player's listing menu.
- `/ah search <text>` searches listing item and seller names.
- Tagged GUI buttons, cancelled inventory clicks/drags, item detail view, paging, and chat search.
- YAML persistence uses a temporary file and replacement.
- Timestamp-based expiration changes active listings to `RETURN_PENDING`.
- Basic cancellation and pending-return state handling.
- Price parser unit tests and a GitHub Actions Maven verification workflow.

## Important limitations — not production-ready

This snapshot is a work in progress and **must not be used on a live economy server**.

- Purchase is deliberately disabled in the GUI. No money is withdrawn and no item is delivered. This is intentional because Vault operations and file writes cannot be made atomic by this implementation.
- Transaction history, complete transaction journal/reconciliation, safe automatic pending-item claims, robust crash-safe return delivery, admin recovery tooling, SQLite, MySQL, and PlaceholderAPI are not implemented.
- The current cancellation delivery sequence is not crash-atomic between inventory mutation and the YAML status write. Treat pending-return records as requiring manual backup/review.
- The inventory GUI has a compact dark theme, but not every GUI setting is wired to its YAML layout file yet.
- No claim is made that compilation, automated tests, Paper/Purpur runtime behavior, or the full listing/purchase lifecycle have passed. Check the Actions tab for actual build results.

## Commands

- `/ah` — browse listings.
- `/ah sell <price>` — list the item in your main hand.
- `/ah mine` — view your active and pending-return listings.
- `/ah search <text>` — search listings.
- `/ah view <player>` — view listings matching a seller name.
- `/ah history` — reserved; history is not implemented.
- `/ah admin reload` — reload Bukkit configuration.

## Permissions

- `superauction.use`
- `superauction.sell`
- `superauction.history`
- `superauction.mute`
- `superauction.admin`
- `superauction.admin.reload`
- `superauction.admin.inspect`
- `superauction.admin.recover`

## Storage

YAML is the only implemented backend. If `plugins/SuperAuction/auctions.yml` contains data that fails to load, preserve a backup and inspect the server log before changing or deleting it. Do not switch storage types expecting a migration; SQLite and MySQL are not implemented yet.

## Safety stance

Features that could charge money without a reliable recovery journal are disabled rather than simulated. Before a production release, implement and test a durable transaction ledger, idempotent economy reconciliation, recoverable item delivery, and crash-safe returns.
