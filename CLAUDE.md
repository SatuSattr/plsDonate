# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
mvn clean package   # builds shaded JAR to target/
mvn compile         # compile only
```

No test suite exists. Output JAR includes all shaded dependencies (HikariCP, javax.mail, ConfigUpdater).

## What This Is

A Bukkit/Paper Minecraft plugin (Java 21, Paper 1.21 API) that integrates with the Tako.id donation platform. Players run `/donate`, a payment link is generated via the Tako.id API, and when payment completes Tako.id sends a webhook back to the plugin's embedded HTTP server, which fulfills rewards and broadcasts notifications.

## Architecture

**Request flow:**
```
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

**Bedrock support:** Optional. Detected at startup via soft dependencies on Geyser + Floodgate. `BedrockFormHandler` shows custom UI forms; gracefully disabled if libs absent.

**PlaceholderAPI:** Optional soft dependency. `PlsDonateExpansion` registers `%plsdonate_*%` placeholders if PAPI is present.

## Configuration System

Three user-facing config files, auto-created from `src/main/resources/` defaults on first run:

- `config.yml` — API key, webhook port/path, SMTP credentials, feature toggles
- `triggers.yml` — conditional reward rules: `if amount >= X` → run commands (supports `{player}`, `{amount}`, `{message}` placeholders)
- `lang/en-US.yml` — all player-facing strings; placeholders documented inline

`ConfigUpdater` (shaded to `click.sattr.plsDonate.libs.configupdater`) merges new config keys into existing files on plugin reload without overwriting user values.

## Important Patterns

- **Replay attack prevention:** `TransactionRepository` marks each transaction as used atomically; `WebhookManager` rejects already-processed IDs.
- **Offline triggers:** Commands for offline players are stored in the `offline_triggers` SQLite table and executed on `PlayerJoinEvent` via `OfflineTriggerRepository`.
- **Sanitization:** Player names and donation messages are run through `sanitize()` before being substituted into commands — don't bypass this.
- **Async database calls:** All DB operations run off the main thread; results that touch Bukkit API are dispatched back via `Bukkit.getScheduler().runTask()`.
