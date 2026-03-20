# Project Refactoring Plan - plsDonate-Express

## Objective
To improve code maintainability, readability, and project structure to make it easier to manage and open for community contributions without removing any existing features.

---

## 1. Domain-Driven Package Restructuring
Move classes from the root package `click.sattr.plsDonate` into functional sub-packages.
- `click.sattr.plsDonate.command`: `DonateCommand`, `plsDonateCommand`
- `click.sattr.plsDonate.database`: `StorageManager` (and future Repositories)
- `click.sattr.plsDonate.manager`: `TriggersManager`, `EmailManager`
- `click.sattr.plsDonate.webhook`: `WebhookManager`
- `click.sattr.plsDonate.platform`: (Keep existing structure)
- `click.sattr.plsDonate.util`: `ExpressionEvaluator`, `MessageUtils`, `ConfigManager`

## 2. Decoupling the "God Class" (PlsDonate.java)
Reduce the size of the main plugin class by moving non-lifecycle logic to specialized managers.
- **MessageUtils**: Handle MiniMessage parsing, color codes, and sending formatted messages.
- **ConfigManager**: Manage `config.yml` loading, updating, and reloading logic.
- **LangManager**: Specifically handle multi-language file loading and placeholder mapping.

## 3. Database Layer Optimization (Repository Pattern)
Improve how data is accessed and stored in SQLite.
- **HikariCP Integration**: Implement a connection pool for better performance and thread safety.
- **Repositories**: Split `StorageManager` logic into:
    - `TransactionRepository`: SQL queries related to donations.
    - `OfflineTriggerRepository`: SQL queries related to offline rewards.
- **Async Database Operations**: Ensure all I/O is consistently offloaded from the main thread.

## 4. Service Pattern for Core Logic
Move business logic out of Command classes and into Services.
- **DonationService**: Handle the core logic of processing a donation (validation -> API call -> database entry -> triggers -> notification).
- **Command Classes**: Focus only on input validation, permission checks, and argument parsing.

## 5. Elimination of Magic Strings
Centralize constants to make the codebase more robust against typos and easier to update.
- **Constants Class**: Store static final strings for placeholders, default values, and internal keys.
- **Permissions Class**: Store all permission nodes (e.g., `plsdonate.admin`, `plsdonate.cooldown.bypass`).

---

## Execution Roadmap

### Phase 1: Foundation & Utils (Current Goal)
- [ ] Create `MessageUtils` and move message parsing logic.
- [ ] Create `Constants` for placeholders and permissions.
- [ ] Move utility methods from `PlsDonate.java`.

### Phase 2: Structural Reorganization
- [ ] Create sub-packages.
- [ ] Move files to their respective packages.
- [ ] Fix all imports and class references.

### Phase 3: Database & Service Layer
- [ ] Implement HikariCP for SQLite.
- [ ] Refactor `StorageManager` into Repositories.
- [ ] Implement `DonationService` to decouple `DonateCommand`.

### Phase 4: Final Cleanup & Documentation
- [ ] Review all classes for "Magic Strings".
- [ ] Update `GEMINI.md` and `REFACTORING.md` with the new structure.
- [ ] Final build verification.
