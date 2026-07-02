# plsDonate — AGENTS.md

## Build & Test

```bash
mvn clean package          # shaded JAR → target/
mvn compile                # compile only
mvn test                   # all tests (JUnit 5, surefire 3.2.5)
mvn test -Dtest=ExpressionEvaluatorTest
mvn test -Dtest=ExpressionEvaluatorTest#divisionByZeroIsRejected
```

CI uses `.m2` caching + `-nsu` for stable SNAPSHOT fetching.

## Architecture

**Entrypoint:** `PlsDonate.java` — initialises all subsystems in `onEnable()`, owns all manager instances.

**Request flow:**
```
/donate → TakoPlatform → payment link
                              ↓
WebhookManager (com.sun.net.httpserver) ← Tako.id callback
    → TransactionRepository (atomic CAS claim)
    → DonationService → [notify, triggers, email, Discord]
```

**Package layout:**
- `manager/` — DonationService (fulfillment orchestration), DiscordManager (embed builder), EmailManager, TriggersManager, StatsManager (leaderboard/milestone cache), BedrockFormHandler
- `database/` — DatabaseManager (HikariCP pool), `repository/TransactionRepository`, `repository/OfflineTriggerRepository`
- `platform/` — DonationPlatform interface (swappable backends), `tako/TakoPlatform`
- `webhook/` — WebhookManager (embedded HTTP server, HMAC validation)
- `command/` — DonateCommand, plsDonateCommand
- `util/` — ExpressionEvaluator (recursive-descent math parser), MessageUtils (formatting + lang resolution), Constants, PlsDonateExpansion (PAPI bridge)

**Config files (auto-created from resources on first run):** `config.yml`, `triggers.yml`, `lang/en-US.yml`.

**Soft dependencies (loaded at startup if present, graceful fallback):** `floodgate`, `Geyser-Spigot`/`Geyser`, `PlaceholderAPI`, `SkinsRestorer`.

## Critical Patterns

- **Ledger gating is intentional:** Only `/donate` (in-game) records transactions in the ledger. A webhook for an unknown `tx_id` is rejected as "unrecorded" — prevents misrouted rewards from external donation pages.
- **Replay attack prevention:** `TransactionRepository.claimTransaction()` runs `UPDATE ... SET status='COMPLETED' WHERE tx_id=? AND status='PENDING'` atomically. The caller that gets `rows-affected == 1` proceeds. **Do not** revert to check-then-mark.
- **Offline triggers:** Commands queued in `offline_triggers` SQLite table, executed on `PlayerJoinEvent`.
- **Sanitize everything:** Pass player names and messages through `sanitize()` before command substitution.
- **Async DB, sync Bukkit:** DB ops off main thread; Bukkit API calls dispatched via `Bukkit.getScheduler().runTask()`.
- **Lang file for display labels:** Use `MessageUtils.formatStatus()`, `formatType()`, `friendlyMethod()` for status / type / method display. Never hardcode colours or labels in command handlers.
- **Discord injection surfaces:** Text fields → Gson `addProperty()` (data). URL fields → `URLEncoder` per-value. `{PLAYER_HEAD}` and `{PLAYER_HEAD_SKIN_RESTORER}` are pre-encoded and substituted verbatim (never double-encoded). Size variants `{PLAYER_HEAD_<size>}` / `{PLAYER_HEAD_SKIN_RESTORER_<size>}` are resolved via regex before fixed-name substitution. All head placeholders render via `vzge.me`. `buildPayload()` takes an explicit `headUrlSr` parameter (pass `null` in tests — auto-falls back to VZGE player-name URL).
- **DonationPlatform interface:** Currently only `TakoPlatform` implements it. `createDonation()` returns `CompletableFuture<DonationResponse>`. `parseWebhook()` returns `WebhookResult`.
- **On reload:** Only `webhookManager.stop()`/`start()` (recreates server), `emailManager.reload()`, config/lang reload. `discordManager` and `donationPlatform` are **never** recreated (avoids leaking HttpClient thread pools).

## Testing Notes

**No mocking framework.**
- **Unit tests** (pure logic, no Bukkit): `DiscordManagerTest` (`buildPayload()` is static), `ExpressionEvaluatorTest`, `MessageUtilsTest` (with/without lang config). Standard JUnit 5 assertions.
- **Integration tests** (real SQLite on temp files): `TransactionRepositoryClaimTest`, `TransactionRepositoryHistoryTest`. `sqlite-jdbc` is test-scoped only — **not** bundled in the shaded JAR (provided by server at runtime).
- All integration tests create `Connection` via `DriverManager.getConnection("jdbc:sqlite:...")` in `@BeforeEach` using temp files, teardown in `@AfterEach`.

## Dependency Quirks

- `paper-api` and `floodgate` are upstream SNAPSHOT-only. Compiles against **oldest** supported Paper API (`1.21-R0.1-SNAPSHOT`) so one JAR runs on 1.21–26.x. Raising api-version shrinks server range.
- Shaded libs: HikariCP, javax.mail, ConfigUpdater (→ `click.sattr.plsDonate.libs.configupdater`), bStats (→ `click.sattr.plsDonate.libs.bstats`).
