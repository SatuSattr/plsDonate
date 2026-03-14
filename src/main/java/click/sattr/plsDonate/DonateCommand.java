package click.sattr.plsDonate;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class DonateCommand implements CommandExecutor, TabCompleter {

    private final PlsDonate plugin;
    private final Map<UUID, String> pendingConfirmations = new ConcurrentHashMap<>();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$");

    public DonateCommand(PlsDonate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{COMMAND}", label);
            plugin.sendLangMessage(sender, "invalid-usage", p);
            return true;
        }

        if (args[0].equalsIgnoreCase("help")) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            sender.sendMessage(plugin.parseMessage("<gray>------ <green>plsDonate Help <gray>------<newline>", p));
            sender.sendMessage(plugin.parseMessage("    <yellow>/pdn help <gray>- Show this help message", p));
            if (sender.hasPermission("plsdonate.admin")) {
                sender.sendMessage(plugin.parseMessage("    <yellow>/pdn fakedonate <amount> <email> [msg] <gray>- Simulate a real donation", p));
                sender.sendMessage(plugin.parseMessage("    <yellow>/pdn reload <gray>- Reload configuration", p));
            }
            sender.sendMessage(plugin.parseMessage("<newline><gray>----------------------------", p));
            return true;
        }

        if (args[0].equalsIgnoreCase("test")) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{PLAYER}", sender.getName());
            placeholders.put("{AMOUNT}", "10000");
            placeholders.put("{EMAIL}", "test@gmail.com");
            placeholders.put("{MESSAGE}", "This is a dummy test message.");
            placeholders.put("{COMMAND}", "/pdn confirm"); // Dummy command

            plugin.sendLangMessageList(sender, "donation-confirmation-java", placeholders);
            return true;
        }

        if (args[0].equalsIgnoreCase("fakedonate")) {
            if (!sender.hasPermission("plsdonate.admin")) {
                plugin.sendLangMessage(sender, "no-permission");
                return true;
            }
            if (!(sender instanceof Player player)) {
                Map<String, String> pOnly = new HashMap<>();
                pOnly.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("command-only-players", "{PREFIX} <red>Only players can execute this command.</red>"), pOnly));
                return true;
            }

            if (args.length < 3) {
                if (args.length == 1 && plugin.getBedrockFormHandler() != null && plugin.getConfig().getBoolean("bedrock-support", false) && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                    plugin.getBedrockFormHandler().openFakeDonateForm(player);
                    return true;
                }

                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                p.put("{COMMAND}", label + " fakedonate");
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-usage", "<red>Invalid usage. <reset>try to run <yellow>/{COMMAND} help<reset> for help"), p));
                return true;
            }

            double amount;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("general-error", "<red>Invalid amount format."), p));
                return true;
            }

            String email = args[2];
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-email", "<red>Invalid email format."), p));
                return true;
            }

            String message = "";
            if (args.length > 3) {
                StringBuilder sb = new StringBuilder();
                for (int i = 3; i < args.length; i++) {
                    sb.append(args[i]).append(" ");
                }
                message = sb.toString().trim();
            }

            StringBuilder fullCommandBuilder = new StringBuilder("/").append(label);
            for (String arg : args) {
                fullCommandBuilder.append(" ").append(arg);
            }
            String fullCommand = fullCommandBuilder.toString();
            UUID uuid = player.getUniqueId();

            if (pendingConfirmations.containsKey(uuid) && pendingConfirmations.get(uuid).equals(fullCommand)) {
                pendingConfirmations.remove(uuid);

                String formattedAmount = plugin.formatIndonesianNumber(amount);
                String txId = "FAKETX-" + System.currentTimeMillis();

                // 1. Broadcast Global Notifications
                if (plugin.getConfig().getBoolean("donate.notification", true)) {
                    Map<String, String> p = new HashMap<>();
                    p.put("{PLAYER}", player.getName());
                    p.put("{AMOUNT}", String.valueOf((long) amount));
                    p.put("{AMOUNT_FORMATTED}", formattedAmount);
                    p.put("{MESSAGE}", message);
                    p.put("{EMAIL}", email);
                    p.put("{METHOD}", "fakedonate");
                    p.put("{ID}", txId);
                    p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));

                    plugin.sendLangMessageList(Bukkit.getConsoleSender(), "donation-notification", p);
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        plugin.sendLangMessageList(onlinePlayer, "donation-notification", p);
                        plugin.playConfigSounds(onlinePlayer, "sound-effects.donation-received");
                    }
                }

                // 2. Trigger processing system
                if (plugin.getTriggersManager() != null) {
                    plugin.getTriggersManager().processDonation(player.getName(), amount, formattedAmount, message, "fakedonate", txId);
                }

                Map<String, String> fP = new HashMap<>();
                fP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("fake-donation-triggered", "{PREFIX} <green>Fake donation successfully triggered!</green>"), fP));

            } else {
                if (plugin.getConfig().getBoolean("donate.confirmation", true) && plugin.getBedrockFormHandler() != null && plugin.getConfig().getBoolean("bedrock-support", false) && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                    plugin.getBedrockFormHandler().sendConfirmationForm(player, amount, email, message, true);
                    return true;
                }

                pendingConfirmations.put(uuid, fullCommand);

                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                p.put("{PLAYER}", player.getName());
                p.put("{AMOUNT}", String.valueOf((long) amount));
                p.put("{AMOUNT_FORMATTED}", plugin.formatIndonesianNumber(amount));
                p.put("{EMAIL}", email);
                p.put("{MESSAGE}", message.isEmpty() ? "No message" : message);
                p.put("{COMMAND}", fullCommand);

                plugin.sendLangMessageList(player, "donation-confirmation-java", p);
                plugin.playConfigSounds(player, "sound-effects.donation-confirmation");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("plsdonate.admin")) {
                plugin.sendLangMessage(sender, "no-permission");
                return true;
            }
            plugin.reloadPlugin();
            plugin.sendLangMessage(sender, "reload-success");
            return true;
        }

        Map<String, String> p = new HashMap<>();
        p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
        p.put("{COMMAND}", label);
        plugin.sendLangMessage(sender, "invalid-usage", p);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            if ("fakedonate".startsWith(sub) && sender.hasPermission("plsdonate.admin")) {
                completions.add("fakedonate");
            }
            if ("reload".startsWith(sub) && sender.hasPermission("plsdonate.admin")) {
                completions.add("reload");
            }
            if ("help".startsWith(sub)) {
                completions.add("help");
            }
        }
        return completions;
    }
}
