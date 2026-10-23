# SuperAuction

SuperAuction is an original Minecraft auction-house plugin project for Paper/Purpur 1.21.11, using Java 21 and Maven. The GUI direction follows the supplied reference's compact, readable Minecraft inventory presentation while keeping original branding and implementation.

## Current development status

This repository is being built in phases. Do not treat a source commit or a successful compilation as proof that every transaction-recovery scenario is safe. The release checklist will report implemented features and tests honestly.

- Target API: Paper 1.21.11
- Java: 21
- Build: Maven
- Economy: Vault hook planned
- Initial storage target: YAML with atomic replacement
- Folia: unsupported unless separately implemented and tested

## Build

Install JDK 21 and Maven, then run:

```sh
mvn clean verify
```

The plugin JAR will be created under `target/`.

## Install

1. Install a compatible Paper or Purpur 1.21.11 server running Java 21.
2. Install Vault and a Vault-compatible economy plugin.
3. Put `SuperAuction-*.jar` in the server's `plugins` directory.
4. Start the server once to generate configuration and data files.
5. Stop the server before making manual changes to persistent data.

## Commands

- `/ah` — browse active listings
- `/ah sell <price>` — begin listing the item in the main hand
- `/ah mine` — manage your listings
- `/ah history` — view your transaction history
- `/ah search <text>` — search listings
- `/ah view <player>` — view a player's active listings
- `/ah admin reload` — reload supported configuration

## Important limitations

The project is not considered production-ready until item escrow, economy reconciliation, pending delivery/return handling, persistence, GUI protections, and concurrency tests have been implemented and exercised on a real test server. SQLite/MySQL and PlaceholderAPI are not advertised as supported until their implementations are present.