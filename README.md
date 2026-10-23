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
- YAML persistence uses a temporary file, keeps an `auctions.yml.bak` copy before replacement, and uses atomic replacement where supported.
- Optional SQLite listing storage is implemented with a bundled JDBC driver, transactional snapshot writes, WAL mode, full synchronous durability, a busy timeout, and a status/creation index. Select it with `storage.type: sqlite`.
- Invalid YAML or malformed escrow records now stop plugin startup instead of being silently skipped and later overwritten.
- Search length and auction browser page size honor the bounded configuration values.
- Admin stats report pending listings, pending returns, and records flagged for manual review.
- Timestamp-based expiration changes active listings to `RETURN_PENDING`. Cancellation records `RETURN_DELIVERY_STARTED` before inventory mutation so ambiguous outcomes require manual review.
- Basic cancellation and pending-return state handling.
- Price parser unit tests and a GitHub Actions Maven verification workflow.
- Append-only transaction journal foundation with forced disk writes, validated state transitions, fail-closed loading, and tests.
- Personal `/ah history` output backed by the journal, plus `/ah admin transaction <id>` and journal review counts.
- Vault purchase flow with durable listing reservation, buyer withdrawal, item delivery, seller payout, and append-only transaction stages; ambiguous failures are flagged for manual review instead of automatic retries.
- Optional PlaceholderAPI expansion: `%superauction_active_listings%`, `%superauction_total_records%`, `%superauction_player_listings%`, and `%superauction_player_transactions%`.

## Production validation status

Automated CI verifies compilation, unit tests, and the shaded JAR contents. This is not a substitute for a full live-server release test. Before enabling on a live economy server, verify YAML and SQLite startup on the exact Paper/Purpur build, list/cancel/expire flows, full-inventory handling, and recovery after forced process termination. Purchase flow now has conservative runtime handling, but it still requires live Paper/Purpur testing and economy-provider verification before production use.

## Important limitations — not production-ready

This snapshot is a work in progress and **must not be used on a live economy server**.

- Purchase processing is implemented for the normal online path using Vault. Vault economy changes, Minecraft inventory delivery, and disk writes are separate systems and cannot be made one atomic transaction. If a step is uncertain, the listing/transaction is reserved for administrator review; the plugin does not automatically retry or blindly refund.
- Full automated reconciliation, idempotent Vault refunds/payouts, and automatic pending-item claims are not implemented. Interrupted purchases are marked for manual review at startup and must be reconciled by an administrator. MySQL is not implemented. PlaceholderAPI placeholders are available when PlaceholderAPI is installed.
- Return delivery now writes `RETURN_DELIVERY_STARTED` durably before touching the player inventory. If a crash or save failure occurs after that point, the record is exposed for manual review and is never automatically redelivered. This avoids automatic duplication, but cannot guarantee exactly-once delivery across Minecraft inventory and disk writes; an administrator must reconcile ambiguous records.
- A listing confirmation interrupted at an ambiguous point is kept non-purchasable and flagged for review rather than silently deleting its escrow record.
- The inventory GUI has a compact dark theme, but not every GUI setting is wired to its YAML layout file yet.
- No claim is made that compilation, automated tests, Paper/Purpur runtime behavior, or the full listing/purchase lifecycle have passed. Check the Actions tab for actual build results.

## Commands

- `/ah` — browse listings.
- `/ah sell <price>` — list the item in your main hand.
- `/ah mine` — view your active and pending-return listings.
- `/ah search <text>` — search listings.
- `/ah view <player>` — view listings matching a seller name.
- `/ah history` — show up to 10 journal transactions involving you.
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

YAML and SQLite listing stores are implemented. YAML data is stored in `plugins/SuperAuction/auctions.yml` with a backup at `auctions.yml.bak`. SQLite data is stored in `plugins/SuperAuction/auctions.db`. Changing `storage.type` does not migrate or copy data between backends; back up the server and perform a deliberate migration before switching. MySQL is not implemented, and selecting it will stop plugin startup instead of silently falling back.

## Safety stance

Purchase processing uses a durable journal and conservative manual-review states, but this does not make Vault calls and inventory mutations atomic. Do not deploy to a live economy until CI passes and forced-crash tests have been completed on the exact Paper/Purpur build and economy provider. Review all `NEEDS_REVIEW` transactions and `FAILED_REQUIRES_REVIEW` listings manually. SQLite support covers listing persistence only; it does not make inventory delivery and Vault transactions atomic.
