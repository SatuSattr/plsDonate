# GEMINI.md - plsDonate-Express

## Project Overview
**plsDonate-Express** is a Minecraft Paper plugin (Java 21, Paper 1.21) designed to provide a donation system integrated with the **tako.id** platform. It allows players to initiate donations in-game and receive rewards or trigger server-side actions once the payment is confirmed via webhooks.

### Key Technologies
- **Java 21**: The project uses modern Java features and targets the latest versions of Minecraft.
- **Paper API (1.21)**: The core API for server-side functionality.
- **Floodgate API & Cumulus**: Provides support for Bedrock Edition players, including custom UI forms.
- **HikariCP**: Connection pooling for robust database management (SQLite/MySQL).
- **JavaMail API**: An integrated SMTP system for sending payment links directly to player emails.
- **Tako.id Integration**: Uses the `tako.id` API for donation creation and receives updates through an embedded HTTP server (webhook listener).

### Core Architecture
- **`PlsDonate.java`**: Main plugin class handling subsystem initialization and configuration management.
- **`DonationService`**: Orchestrates the donation flow between the platform and the game.
- **`DonationPlatform`**: Abstract layer for donation services (implemented by `TakoPlatform`).
- **`WebhookManager`**: An embedded server that listens for incoming payment notifications.
- **`TriggersManager`**: Executes configured commands or actions (e.g., broadcasting messages, giving items) upon successful donation.
- **`Repository Pattern`**: Uses `TransactionRepository` and `OfflineTriggerRepository` for database interactions.

---

## Building and Running

### Building the Project
The project uses Maven for dependency management and builds.
```bash
# Clean and package the plugin into a JAR file
mvn clean package
```
The output JAR file will be located in the `target/` directory: `target/plsDonate-Express-1.0.jar`.

### Installation
1. Place the generated JAR file into the `plugins/` folder of your Paper/Spigot server.
2. Restart the server to generate the default configuration files.
3. Configure `config.yml` with your `tako.id` credentials and webhook settings.
4. Ensure the configured webhook port is open in your firewall.

---

## Development Conventions

### Coding Style
- **Naming**: Standard Java CamelCase conventions.
- **Messages**: Uses `MessageUtils` for parsing MiniMessage and legacy color codes.
- **Constants**: Global configuration keys, permissions, and placeholders are centralized in `click.sattr.plsDonate.util.Constants`.

### Configuration Management
- **`ConfigUpdater`**: Automatically updates configuration files (`config.yml`, `lang/en-US.yml`) while preserving user comments.
- **Language Support**: All player-facing messages are externalized in the `lang/` directory.

### Commands & Permissions
- **`/donate <amount> <email> <method> [message]`**: The primary command for players to start a donation.
- **`/plsdonate`**: Admin management command.
- **Permissions**:
  - `plsdonate.admin`: Grants access to administrative functions and configuration reloads.
  - `plsdonate.cooldown.bypass`: Allows skipping the donation command cooldown.

---

## Key Files & Directories
- `src/main/resources/config.yml`: Main configuration for API keys, webhooks, and SMTP settings.
- `src/main/resources/triggers.yml`: Defines actions to execute when a donation is received.
- `src/main/resources/lang/`: YAML files for localization.
- `src/main/java/click/sattr/plsDonate/webhook/`: Contains the webhook listener logic.
- `src/main/java/click/sattr/plsDonate/manager/`: Core business logic managers (Email, Triggers, Bedrock support).
