package click.sattr.plsDonate;

import click.sattr.plsDonate.command.DonateCommand;
import click.sattr.plsDonate.command.plsDonateCommand;
import click.sattr.plsDonate.database.DatabaseManager;
import click.sattr.plsDonate.database.repository.OfflineTriggerRepository;
import click.sattr.plsDonate.database.repository.TransactionRepository;
import click.sattr.plsDonate.manager.BedrockFormHandler;
import click.sattr.plsDonate.manager.DonationService;
import click.sattr.plsDonate.manager.EmailManager;
import click.sattr.plsDonate.manager.TriggersManager;
import click.sattr.plsDonate.platform.DonationPlatform;
import click.sattr.plsDonate.platform.tako.TakoPlatform;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.webhook.WebhookManager;
import com.tchristofferson.configupdater.ConfigUpdater;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class PlsDonate extends JavaPlugin implements Listener {

    private FileConfiguration langConfig;
    private WebhookManager webhookManager;
    
    private DatabaseManager databaseManager;
    private TransactionRepository transactionRepository;
    private OfflineTriggerRepository offlineTriggerRepository;
    
    private DonationPlatform donationPlatform;
    private TriggersManager triggersManager;
    private EmailManager emailManager;
    private DonationService donationService;
    private BedrockFormHandler bedrockFormHandler;
    private DonateCommand donateCommand;
    private plsDonateCommand pdnCommand;

    // Getters for subsystems
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public TransactionRepository getTransactionRepository() { return transactionRepository; }
    public OfflineTriggerRepository getOfflineTriggerRepository() { return offlineTriggerRepository; }
    
    public DonationPlatform getDonationPlatform() { return donationPlatform; }
    public FileConfiguration getLangConfig() { return langConfig; }
    public TriggersManager getTriggersManager() { return triggersManager; }
    public EmailManager getEmailManager() { return emailManager; }
    public DonationService getDonationService() { return donationService; }
    public BedrockFormHandler getBedrockFormHandler() { return bedrockFormHandler; }
    
    @Override
    public void onEnable() {
        // Create plugin folder if it doesn't exist
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        
        // If config doesn't exist, save default
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        } else {
            // If it exists, update it
            try {
                ConfigUpdater.update(this, "config.yml", configFile, Collections.emptyList());
            } catch (IOException e) {
                getLogger().severe("Could not update config.yml!");
                e.printStackTrace();
            }
        }

        // Now reload to make sure we have the latest data
        reloadConfig();
        loadLanguageConfig();

        // Initialize Database & Repositories
        databaseManager = new DatabaseManager(this);
        transactionRepository = new TransactionRepository(this, databaseManager);
        offlineTriggerRepository = new OfflineTriggerRepository(this, databaseManager);
        
        // Initialize Triggers Manager
        triggersManager = new TriggersManager(this);

        // Initialize Donation Service
        donationService = new DonationService(this);

        // Register Donate Command
        donateCommand = new DonateCommand(this);
        getCommand("donate").setExecutor(donateCommand);
        getCommand("donate").setTabCompleter(donateCommand);

        pdnCommand = new plsDonateCommand(this);
        getCommand("plsdonate").setExecutor(pdnCommand);
        
        getServer().getPluginManager().registerEvents(this, this);

        loadActivePlatform();
        
        emailManager = new EmailManager(this);
        
        // Initialize Bedrock/Floodgate Handler if enabled and installed
        if (getConfig().getBoolean(Constants.CONF_BEDROCK_SUPPORT, false) && getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrockFormHandler = new BedrockFormHandler(this);
                getLogger().info("Geyser/Floodgate detected! Bedrock UI support enabled.");
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Bedrock forms although floodgate was detected.");
            }
        }

        // Mandatory Webhook Initialization
        webhookManager = new WebhookManager(this);
        int port = getConfig().getInt(Constants.CONF_WEBHOOK_PORT, Constants.DEFAULT_WEBHOOK_PORT);
        String path = getConfig().getString(Constants.CONF_WEBHOOK_PATH, Constants.DEFAULT_WEBHOOK_PATH);
        
        if (!webhookManager.start(port, path)) {
            getLogger().severe("Disabling plugin due to mandatory webhook failure!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Delayed startup message to appear after "Done!"
        Bukkit.getScheduler().runTask(this, () -> {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put(Constants.PREFIX, langConfig.getString("prefix", Constants.DEFAULT_PREFIX));
            placeholders.put("{PORT}", String.valueOf(getConfig().getInt(Constants.CONF_WEBHOOK_PORT, Constants.DEFAULT_WEBHOOK_PORT)));
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage(langConfig.getString("startup-success", "{PREFIX} <green>plsDonate version " + getPluginMeta().getVersion() + " loaded!</green>"), placeholders));
            
            checkImportantConfigs();
        });
    }

    private void checkImportantConfigs() {
        Map<String, String> p = new HashMap<>();
        p.put(Constants.PREFIX, langConfig.getString("prefix", Constants.DEFAULT_PREFIX));

        String takoToken = getConfig().getString(Constants.CONF_TAKO_TOKEN, "your_secret_token_here");
        if (takoToken.isEmpty() || "your_secret_token_here".equals(takoToken)) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] Tako.id Webhook Token is not set! (" + Constants.CONF_TAKO_TOKEN + ")</red>", p));
        }

        String takoCreator = getConfig().getString(Constants.CONF_TAKO_CREATOR, "");
        if (takoCreator.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] Tako.id Creator is empty! (" + Constants.CONF_TAKO_CREATOR + ")</red>", p));
        }

        String takoKey = getConfig().getString(Constants.CONF_TAKO_KEY, "your_secret_api_key_here");
        if (takoKey.isEmpty() || "your_secret_api_key_here".equals(takoKey)) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] Tako.id API Key is empty! (" + Constants.CONF_TAKO_KEY + ")</red>", p));
        }

        // Email hosts check
        org.bukkit.configuration.ConfigurationSection hosts = getConfig().getConfigurationSection("email.hosts");
        if (hosts == null || hosts.getKeys(false).isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] 'email.hosts' is missing/empty in config.yml! Payment emails will not be sent.</red>", p));
        } else {
            boolean hasValidHost = false;
            for (String hostKey : hosts.getKeys(false)) {
                String user = getConfig().getString("email.hosts." + hostKey + ".user", "");
                String host = getConfig().getString("email.hosts." + hostKey + ".host", "");
                if (!user.isEmpty() && !host.isEmpty() && !"email@gmail.com".equalsIgnoreCase(user)) {
                    hasValidHost = true;
                    break;
                }
            }
            if (!hasValidHost) {
                Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] All SMTP hosts are using default/blank credentials! Payment emails will not work.</red>", p));
            }
        }
    }

    private void loadLanguageConfig() {
        String langName = getConfig().getString("language", "en-US");
        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) langFolder.mkdirs();

        File langFile = new File(langFolder, langName + ".yml");
        
        if (!langFile.exists()) {
            try {
                saveResource("lang/" + langName + ".yml", false);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Language file lang/" + langName + ".yml not found in resources! Falling back to en-US.yml");
                langName = "en-US";
                langFile = new File(langFolder, "en-US.yml");
                if (!langFile.exists()) {
                    saveResource("lang/en-US.yml", false);
                }
            }
        }
        
        try {
            String resourcePath = "lang/" + langName + ".yml";
            try {
                ConfigUpdater.update(this, resourcePath, langFile, Collections.emptyList());
            } catch (Exception e) {
                ConfigUpdater.update(this, "lang/en-US.yml", langFile, Collections.emptyList());
            }
        } catch (IOException e) {
            getLogger().severe("Could not update language file: " + langFile.getName());
            e.printStackTrace();
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);
        getLogger().info("Language loaded: " + langName);
    }

    @Override
    public void onDisable() {
        if (webhookManager != null) {
            webhookManager.stop();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    public void reloadPlugin() {
        getLogger().info("Reloading plsDonate-Express configuration...");
        if (webhookManager != null) {
            webhookManager.stop();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        try {
            ConfigUpdater.update(this, "config.yml", configFile, Collections.emptyList());
        } catch (IOException e) {
            getLogger().severe("Could not update config.yml during reload!");
            e.printStackTrace();
        }
        
        reloadConfig();
        loadLanguageConfig();
        loadActivePlatform();

        if (triggersManager != null) {
            triggersManager.loadConfig();
        }
        
        if (emailManager != null) {
            emailManager.reload();
        }

        if (bedrockFormHandler == null && getConfig().getBoolean(Constants.CONF_BEDROCK_SUPPORT, false) && getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrockFormHandler = new BedrockFormHandler(this);
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Bedrock forms during reload.");
            }
        }

        int port = getConfig().getInt(Constants.CONF_WEBHOOK_PORT, Constants.DEFAULT_WEBHOOK_PORT);
        String path = getConfig().getString(Constants.CONF_WEBHOOK_PATH, Constants.DEFAULT_WEBHOOK_PATH);
        
        if (!webhookManager.start(port, path)) {
            getLogger().severe("Failed to restart mandatory webhook listener during reload!");
        }

        checkImportantConfigs();
        getLogger().info("plsDonate-Express reload complete.");
    }

    public void loadActivePlatform() {
        donationPlatform = new TakoPlatform(this);
        getLogger().info("Donation Platform: Tako.id Enabled (Express Version)");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (donateCommand != null) {
            donateCommand.clearPendingRequests(event.getPlayer().getUniqueId());
        }
        if (pdnCommand != null) {
            pdnCommand.clearPendingRequests(event.getPlayer().getUniqueId());
        }
    }
}
