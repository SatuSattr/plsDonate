# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
mvn clean package   # builds shaded JAR to target/
mvn compile         # compile only
```

The shaded JAR bundles HikariCP, javax.mail, and ConfigUpdater. The SQLite JDBC driver is **not** bundled — it is provided by the server at runtime (`org.sqlite.JDBC`), which is why `sqlite-jdbc` appears only as a test-scoped dependency.

**Dependency versioning:** `paper-api` and `floodgate` exist upstream only as SNAPSHOTs (no stable releases). The plugin compiles against the _oldest_ supported Paper API (`1.21-R0.1-SNAPSHOT`, matching `api-version` in `plugin.yml`) on purpose, so one JAR runs across 1.21 through current 26.x servers — raising the compile API or `api-version` would shrink that range and break older servers. CI (`.github/workflows/ci.yml`) uses `.m2` caching plus `-nsu` to keep SNAPSHOT fetches stable.

## Test

```bash
mvn test                                                          # all tests (JUnit 5)
mvn test -Dtest=ExpressionEvaluatorTest                           # one class
mvn test -Dtest=ExpressionEvaluatorTest#divisionByZeroIsRejected  # one method
```

Tests live in `src/test/java`. `TransactionRepositoryClaimTest` runs the real ledger queries against a temporary on-disk SQLite database instead of mocking, so it exercises the actual atomic-claim SQL.

## What This Is & The Problem It Solves

A Bukkit/Paper Minecraft plugin (Java 21, Paper 1.21 API) that integrates with the Tako.id donation platform. 

**Context:** Indonesian server owners face high costs for external webstores and difficulty obtaining business licenses for local payment gateways. Furthermore, external webstores cause issues with cross-play (Bedrock players with prefix names like `.Ryan` often typo their names, missing rewards).
**Solution:** This plugin allows players to run `/donate` in-game, hitting the Tako.id API. The exact player name is locked in the payment link. When payment completes, Tako.id sends a webhook back to the plugin's embedded HTTP server, which instantly fulfills rewards natively in-game.

## Architecture

**Request flow:**

```text
/donate command → TakoPlatform (Tako.id API) → player receives payment link
                                                         ↓
WebhookManager (embedded HTTP server) ← Tako.id webhook callback
         ↓
TransactionRepository (replay-attack check + mark used)
         ↓
DonationService → [broadcast notification, run triggers, save to DB, send email]
```

**Key classes:**

- `PlsDonate.java` — main plugin class; initializes all subsystems in `onEnable()`, owns all manager instances
- `DonationService.java` — orchestrates fulfillment: DB persistence, notifications, trigger execution, email
- `WebhookManager.java` — `com.sun.net.httpserver` HTTP server; validates HMAC signatures, delegates to `DonationService`
- `TakoPlatform.java` — implements `DonationPlatform`; handles Tako.id API calls and HMAC signing
- `TriggersManager.java` — parses `triggers.yml`; evaluates conditions and executes commands; queues commands for offline players via `OfflineTriggerRepository`
- `TransactionRepository.java` — SQLite CRUD for the `transactions` table; marks transactions as used after fulfillment
- `DatabaseManager.java` — HikariCP SQLite pool; owns table schema creation

**Platform abstraction:** `DonationPlatform` interface lets alternative payment backends be swapped in. Currently only `TakoPlatform` implements it.

**Bedrock support:** Deeply integrated. Detected at startup via soft dependencies on Geyser + Floodgate. `BedrockFormHandler` shows native Bedrock UI forms for the donation confirmation, drastically improving UX for Bedrock players compared to chat links. Gracefully disabled if libs absent.

**PlaceholderAPI:** Optional soft dependency. `PlsDonateExpansion` registers `%plsdonate_*%` placeholders if PAPI is present.

## Configuration System

Three user-facing config files, auto-created from `src/main/resources/` defaults on first run:

- `config.yml` — API key, webhook port/path, SMTP credentials, feature toggles
- `triggers.yml` — conditional reward rules: `if amount >= X` → run commands (supports `{player}`, `{amount}`, `{message}` placeholders, and math effects)
- `lang/en-US.yml` — all player-facing strings; placeholders documented inline. Fully translatable.

`ConfigUpdater` (shaded to `click.sattr.plsDonate.libs.configupdater`) merges new config keys into existing files on plugin reload without overwriting user values.

## Important Patterns

- **Ledger gating is intentional:** only donations initiated in-game via `/donate` are recorded in the ledger and thus fulfilled. A webhook whose transaction isn't in the ledger is rejected as "unrecorded" — this is a feature (it prevents misrouted rewards from external donation pages), not a bug to fix. `/donate` sends the Minecraft name to Tako.id as the `name` field; Tako.id returns it in the webhook as `gifterName` and locks the amount, so the stored `MD5(txId + amount + name)` checksum round-trips correctly.
- **Replay attack prevention:** fulfillment is gated on an atomic compare-and-set — `TransactionRepository.claimTransaction()` runs `UPDATE ... SET status='COMPLETED' WHERE tx_id=? AND status='PENDING'` and only the caller that transitions the row (rows-affected == 1) proceeds. Do not revert this to a separate check-then-mark; that reintroduces a double-fulfillment race under concurrent webhooks.
- **Offline triggers:** Commands for offline players are stored in the `offline_triggers` SQLite table and executed on `PlayerJoinEvent` via `OfflineTriggerRepository`.
- **Sanitization:** Player names and donation messages are run through `sanitize()` before being substituted into commands — don't bypass this.
- **Async database calls:** All DB operations run off the main thread; results that touch Bukkit API are dispatched back via `Bukkit.getScheduler().runTask()`.
