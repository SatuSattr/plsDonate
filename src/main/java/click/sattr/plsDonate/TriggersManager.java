package click.sattr.plsDonate;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TriggersManager implements Listener {

    private final PlsDonate plugin;
    private FileConfiguration triggersConfig;
    private final Pattern mathPattern = Pattern.compile("\\{math:(.*?)\\}");

    public TriggersManager(PlsDonate plugin) {
        this.plugin = plugin;
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void loadConfig() {
        File triggersFile = new File(plugin.getDataFolder(), "triggers.yml");
        if (!triggersFile.exists()) {
            plugin.saveResource("triggers.yml", false);
        }
        triggersConfig = YamlConfiguration.loadConfiguration(triggersFile);
    }

    public void processDonation(String donorName, double amount, String formattedAmount, String message, String paymentMethod, String txId) {
        if (!plugin.getConfig().getBoolean("triggers.enabled", true)) return;

        // Prepare base placeholders
        if (donorName == null) donorName = "Anonymous";
        if (message == null) message = "";
        if (paymentMethod == null) paymentMethod = "unknown";
        if (txId == null) txId = "local";

        ConfigurationSection triggersSection = triggersConfig.getConfigurationSection("triggers");
        if (triggersSection == null) return;

        for (String triggerKey : triggersSection.getKeys(false)) {
            ConfigurationSection trigger = triggersSection.getConfigurationSection(triggerKey);
            if (trigger == null) continue;

            if (!trigger.getBoolean("enabled", true)) continue;

            List<String> conditions = trigger.getStringList("conditions");
            boolean conditionsMet = true;

            for (String condition : conditions) {
                String processedCondition = formatPlaceholders(condition, donorName, amount, formattedAmount, message, paymentMethod, txId);
                if (!ExpressionEvaluator.evaluateCondition(processedCondition)) {
                    conditionsMet = false;
                    break;
                }
            }

            if (conditionsMet) {
                List<String> commands = trigger.getStringList("commands");
                boolean requireOnline = trigger.getBoolean("require_online", false);

                boolean isOnline = Bukkit.getPlayerExact(donorName) != null;

                for (String command : commands) {
                    String processedCommand = formatPlaceholders(command, donorName, amount, formattedAmount, message, paymentMethod, txId);
                    processedCommand = evaluateMathBlocks(processedCommand);

                    if (requireOnline && !isOnline) {
                        plugin.getStorageManager().insertOfflineTrigger(donorName, processedCommand);
                        plugin.getLogger().info("Saved offline trigger command for " + donorName + " (Waiting for login).");
                    } else {
                        executeCommand(processedCommand);
                    }
                }
            }
        }
    }

    private String formatPlaceholders(String text, String player, double amount, String formattedAmount, String message, String method, String id) {
        return text.replace("{player}", player)
                   .replace("{amount}", String.valueOf(amount))
                   .replace("{amount_formatted}", formattedAmount)
                   .replace("{message}", message)
                   .replace("{method}", method)
                   .replace("{id}", id);
    }

    private String evaluateMathBlocks(String command) {
        Matcher matcher = mathPattern.matcher(command);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String mathExpr = matcher.group(1);
            try {
                double mathResult = ExpressionEvaluator.evaluateMath(mathExpr);
                // If it is a whole number (e.g. 15.0), format it as an integer ("15")
                String resultStr;
                if (mathResult == (long) mathResult) {
                    resultStr = String.format("%d", (long) mathResult);
                } else {
                    resultStr = String.format("%s", mathResult);
                }
                matcher.appendReplacement(result, resultStr);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to evaluate math {" + mathExpr + "} in command: " + command);
                matcher.appendReplacement(result, "0"); // Default fallback
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void executeCommand(String command) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command);
            plugin.getLogger().info("Executed trigger command: " + command);
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("triggers.enabled", true)) return;

        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> offlineCommands = plugin.getStorageManager().getAndRemoveOfflineTriggers(player.getName());
            if (!offlineCommands.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String command : offlineCommands) {
                        executeCommand(command);
                    }
                });
            }
        });
    }
}
