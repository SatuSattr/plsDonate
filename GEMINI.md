# GEMINI.md

This file provides detailed guidance to Gemini (Antigravity/Agentic AI) when working with the `plsDonate` codebase.

## 📌 Project Overview & Problem Solved
`plsDonate` is a Minecraft plugin designed specifically for Indonesian server owners to seamlessly accept donations without the hassle of setting up an external webstore or acquiring complex business licenses for local payment gateways (like Midtrans or Xendit).

### Key Problems Addressed:
1. **High Webstore Costs:** External webstores are expensive and difficult to integrate natively.
2. **Payment Gateway Limits:** Most local payment gateways require business licenses (PT/CV), which is a barrier for many server owners.
3. **Bedrock Cross-Play Issues:** Bedrock players often have a prefix (like `.`) in their names. When filling out manual external donation forms, they often enter their names incorrectly (e.g., `Ryan` instead of `.Ryan`), causing missing rewards and support headaches.
4. **Manual Verification:** Manual donation processing is slow and error-prone.

### The Solution:
The plugin utilizes **Tako.id**, an Indonesian community-driven donation platform. Using their new API, players can execute a simple `/donate` command in-game. The plugin automatically generates a payment link with their exact in-game name pre-filled and locked, sends it to them, and listens for a webhook from Tako.id to instantly deliver rewards via a customizable **Donation Trigger** system. Finances are handled directly by Tako.

## 🏗 Architecture & Request Flow

```text
/donate command → TakoPlatform (Tako.id API) → player receives payment link
                                                         ↓
WebhookManager (embedded HTTP server) ← Tako.id webhook callback
         ↓
TransactionRepository (replay-attack check + mark used)
         ↓
DonationService → [broadcast notification, run triggers, save to DB, send email]
```

## 📂 Key Classes & Codebase Structure
- **`PlsDonate.java`**: Main plugin class; initializes subsystems in `onEnable()`, owns all manager instances.
- **`DonationService.java`**: Orchestrates fulfillment: DB persistence, notifications, trigger execution, email.
- **`WebhookManager.java`**: Embedded HTTP server (`com.sun.net.httpserver`) that validates HMAC signatures and delegates to `DonationService`.
- **`TakoPlatform.java`**: Implements `DonationPlatform`; handles Tako.id API calls and HMAC signing.
- **`TriggersManager.java`**: Parses `triggers.yml`; evaluates conditions and math effects, executes commands; queues commands for offline players via `OfflineTriggerRepository`.
- **`TransactionRepository.java`**: SQLite CRUD for the `transactions` table; implements atomic mark-as-used after fulfillment.
- **`DatabaseManager.java`**: HikariCP SQLite pool; owns table schema creation.

## ✨ Standout Features
- **Native Bedrock Support**: Integrates with GeyserMC & Floodgate to provide a native form confirmation UI for Bedrock players, avoiding clunky chat-based links. Provides high-end UX instead of forcing Bedrock players to use Java UI constraints.
- **Donation Triggers**: Advanced conditional and mathematical sequence of commands defined in `triggers.yml` (e.g., specific rewards based on amount donated, online/offline status, permissions).
- **Offline Triggers Queue**: If a player is offline when the donation arrives, their rewards are safely stored in an SQLite `offline_triggers` table and executed when they join.
- **Atomic Claiming (Replay Protection)**: Ensures webhooks cannot trigger double-fulfillment under concurrent requests.
- **Customizable Language**: Fully translatable player-facing messages in `lang/en-US.yml` (or id-ID).

## 🛠 Build & Test Instructions

### Build
```bash
mvn clean package   # builds shaded JAR to target/
mvn compile         # compile only
```
*Note: The SQLite JDBC driver is not bundled; it is provided by the server at runtime.*

### Test
```bash
mvn test                                                          # all tests (JUnit 5)
mvn test -Dtest=ExpressionEvaluatorTest                           # one class
mvn test -Dtest=ExpressionEvaluatorTest#divisionByZeroIsRejected  # one method
```

## 🔒 Important Patterns & Rules
- **Ledger Gating:** Only donations initiated in-game via `/donate` are recorded in the ledger and fulfilled. The `name` field is passed to Tako.id and returned in the webhook, and we verify the checksum `MD5(txId + amount + name)`.
- **Async Database Calls:** All DB operations run off the main thread; results touching Bukkit API are dispatched back via `Bukkit.getScheduler().runTask()`.
- **Sanitization:** Player names and donation messages are sanitized before being substituted into commands.

---
**When assisting the USER, ensure you maintain these architectural patterns, especially the platform abstractions, async DB calls, and robust Bedrock support.**
