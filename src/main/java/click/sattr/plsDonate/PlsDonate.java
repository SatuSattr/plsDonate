package click.sattr.plsDonate;

import click.sattr.plsDonate.command.DonateCommand;
import click.sattr.plsDonate.command.plsDonateCommand;
import click.sattr.plsDonate.database.DatabaseManager;
import click.sattr.plsDonate.database.repository.OfflineTriggerRepository;
import click.sattr.plsDonate.database.repository.TransactionRepository;
import click.sattr.plsDonate.manager.BedrockFormHandler;
import click.sattr.plsDonate.manager.DiscordManager;
import click.sattr.plsDonate.manager.JavaDialogHandler;
import click.sattr.plsDonate.manager.DonationService;
import click.sattr.plsDonate.manager.EmailManager;
import click.sattr.plsDonate.manager.StatsManager;
import click.sattr.plsDonate.manager.TriggersManager;
import click.sattr.plsDonate.platform.DonationPlatform;
import click.sattr.plsDonate.platform.tako.TakoPlatform;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import click.sattr.plsDonate.webhook.WebhookManager;
import com.tchristofferson.configupdater.ConfigUpdater;
import org.bstats.bukkit.Metrics;
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
import java.util.List;
import java.util.Map;

public final class PlsDonate extends JavaPlugin implements Listener {

    private static final int BSTATS_PLUGIN_ID = 32260;

    private FileConfiguration langConfig;
    private WebhookManager webhookManager;
    private Metrics metrics;
    
    private DatabaseManager databaseManager;
    private TransactionRepository transactionRepository;
    private OfflineTriggerRepository offlineTriggerRepository;
    
    private DonationPlatform donationPlatform;
    private TriggersManager triggersManager;
    private EmailManager emailManager;
    private DonationService donationService;
    private StatsManager statsManager;
    private DiscordManager discordManager;
    private BedrockFormHandler bedrockFormHandler;
    private JavaDialogHandler javaDialogHandler;
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
    public StatsManager getStatsManager() { return statsManager; }
    public DiscordManager getDiscordManager() { return discordManager; }
    public BedrockFormHandler getBedrockFormHandler() { return bedrockFormHandler; }
    public JavaDialogHandler getJavaDialogHandler() { return javaDialogHandler; }
    
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
        
        // Save default templates
        saveDefaultTemplates();
        
        // Auto-toggle bedrock-support if Geyser and Floodgate are detected
        checkAndAutoEnableBedrockSupport();
        
        loadLanguageConfig();

        // Initialize Database & Repositories
        databaseManager = new DatabaseManager(this);
        transactionRepository = new TransactionRepository(this, databaseManager);
        offlineTriggerRepository = new OfflineTriggerRepository(this, databaseManager);
        
        // Initialize Triggers Manager
        triggersManager = new TriggersManager(this);

        // Initialize Donation Service
        donationService = new DonationService(this);

        // Initialize Discord webhook notifications. Reads config per-request, so it is
        // created once here and never recreated on reload (avoids leaking HttpClient pools).
        discordManager = new DiscordManager(this);

        // Initialize stats cache (leaderboard + milestone) and warm it from the DB
        statsManager = new StatsManager(this);
        statsManager.refresh();

        // Register Donate Command
        donateCommand = new DonateCommand(this);
        getCommand("donate").setExecutor(donateCommand);
        getCommand("donate").setTabCompleter(donateCommand);

        pdnCommand = new plsDonateCommand(this);
        getCommand("plsdonate").setExecutor(pdnCommand);
        
        getServer().getPluginManager().registerEvents(this, this);

        loadActivePlatform();
        
        emailManager = new EmailManager(this);

        // Register PlaceholderAPI expansion
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new click.sattr.plsDonate.util.PlsDonateExpansion(this).register();
            getLogger().info("PlaceholderAPI detected! Placeholders registered.");
        }
        
        // Initialize Bedrock/Floodgate Handler if enabled and installed
        if (getConfig().getBoolean(Constants.CONF_BEDROCK_SUPPORT, false) && getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrockFormHandler = new BedrockFormHandler(this);
                getLogger().info("Geyser/Floodgate detected! Bedrock UI support enabled.");
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Bedrock forms although floodgate was detected.");
            }
        }

        // Java Dialog support (1.21.6+)
        try {
            if (JavaDialogHandler.isServerSupported()) {
                javaDialogHandler = new JavaDialogHandler(this);
                getLogger().info("Java Dialog support enabled (1.21.6+)");
            }
        } catch (Throwable t) {
            getLogger().info("Java Dialog not available on this server version.");
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

        // Initialize bStats metrics
        metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        // Delayed startup message to appear after "Done!"
        Bukkit.getScheduler().runTask(this, () -> {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put(Constants.PREFIX, langConfig.getString("prefix", Constants.DEFAULT_PREFIX));
            placeholders.put("{PORT}", String.valueOf(getConfig().getInt(Constants.CONF_WEBHOOK_PORT, Constants.DEFAULT_WEBHOOK_PORT)));
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage(langConfig.getString("startup-success", "{PREFIX} <green>plsDonate version " + getPluginMeta().getVersion() + " loaded!</green>"), placeholders));
            
            if (isLocalEnvironment()) {
                List<String> warningLines = langConfig.getStringList("local-env-warning");
                if (!warningLines.isEmpty()) {
                    for (String line : warningLines) {
                        Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage(line, placeholders));
                    }
                }
            }

            checkImportantConfigs();
        });
    }

    private boolean isLocalEnvironment() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;

                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address) {
                        if (!address.isLoopbackAddress() && !address.isSiteLocalAddress() && !address.isLinkLocalAddress()) {
                            return false; // Found a non-local address
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private void checkAndAutoEnableBedrockSupport() {
        boolean hasGeyser = getServer().getPluginManager().getPlugin("Geyser-Spigot") != null || 
                           getServer().getPluginManager().getPlugin("Geyser") != null;
        boolean hasFloodgate = getServer().getPluginManager().getPlugin("floodgate") != null;

        if (hasGeyser && hasFloodgate) {
            if (!getConfig().getBoolean(Constants.CONF_BEDROCK_SUPPORT, false)) {
                getConfig().set(Constants.CONF_BEDROCK_SUPPORT, true);
                saveConfig();
                getLogger().info("Geyser and Floodgate detected! 'bedrock-support' has been automatically enabled in config.yml.");
            }
        }
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
        List<Map<?, ?>> hostsList = getConfig().getMapList("email.hosts");
        if (hostsList == null || hostsList.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(MessageUtils.parseMessage("{PREFIX} <red>[!] 'email.hosts' is missing/empty in config.yml! Payment emails will not be sent.</red>", p));
        } else {
            boolean hasValidHost = false;
            for (Map<?, ?> hostMap : hostsList) {
                if (hostMap == null) continue;
                String user = String.valueOf(hostMap.get("user"));
                String host = String.valueOf(hostMap.get("host"));
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
        getLogger().info("Reloading plsDonate configuration...");
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

        if (statsManager != null) {
            statsManager.refresh();
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
        getLogger().info("plsDonate reload complete.");
    }

    public void loadActivePlatform() {
        // TakoPlatform reads all config per-request, so it never needs recreating on reload.
        // Recreating would leak the previous instance's HttpClient thread pool.
        if (donationPlatform == null) {
            donationPlatform = new TakoPlatform(this);
            getLogger().info("Donation Platform: Tako.id Enabled");
        }
    }

    private void saveDefaultTemplates() {
        File templatesFolder = new File(getDataFolder(), "templates");
        if (!templatesFolder.exists()) {
            templatesFolder.mkdirs();
        }

        File paymentTemplate = new File(templatesFolder, "payment.html");
        if (!paymentTemplate.exists()) {
            saveResource("templates/payment.html", false);
        }
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
