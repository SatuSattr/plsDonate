package click.sattr.plsDonate;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class plsDonateCommand implements CommandExecutor, TabCompleter {

    private final PlsDonate plugin;
    private final Map<String, FakeDonationRequest> pendingRequests = new ConcurrentHashMap<>();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$");
    private static final Pattern MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    public plsDonateCommand(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public record FakeDonationRequest(UUID playerUuid, double amount, String email, String method, String message) {}

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
            sender.sendMessage(plugin.parseMessage("    <yellow>/pdn leaderboard [--update] <gray>- Show top donators", p));
            sender.sendMessage(plugin.parseMessage("    <yellow>/pdn milestone [--update] <gray>- Show donation goal", p));
            if (sender.hasPermission("plsdonate.admin")) {
                sender.sendMessage(plugin.parseMessage("    <yellow>/pdn fakedonate <amount> <email> <method> [msg] <gray>- Simulate a real donation", p));
                sender.sendMessage(plugin.parseMessage("    <yellow>/pdn reload <gray>- Reload configuration", p));
            }
            sender.sendMessage(plugin.parseMessage("<newline><gray>----------------------------", p));
            return true;
        }

        if (args[0].equalsIgnoreCase("leaderboard")) {
            if (!plugin.getOverlayManager().isConfigured()) {
                plugin.sendLangMessage(sender, "overlay-disabled");
                return true;
            }

            boolean forceUpdate = args.length > 1 && args[1].equalsIgnoreCase("--update");

            if (forceUpdate) {
                plugin.sendLangMessage(sender, "loading-overlay");
                plugin.getOverlayManager().updateCacheAsync().thenAccept(v -> {
                    Bukkit.getScheduler().runTask(plugin, () -> displayLeaderboard(sender));
                });
            } else {
                displayLeaderboard(sender);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("milestone")) {
            if (!plugin.getOverlayManager().isConfigured()) {
                plugin.sendLangMessage(sender, "overlay-disabled");
                return true;
            }

            boolean forceUpdate = args.length > 1 && args[1].equalsIgnoreCase("--update");

            if (forceUpdate) {
                plugin.sendLangMessage(sender, "loading-overlay");
                plugin.getOverlayManager().updateCacheAsync().thenAccept(v -> {
                    Bukkit.getScheduler().runTask(plugin, () -> displayMilestone(sender));
                });
            } else {
                displayMilestone(sender);
            }
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

            // Check if it's a confirmation hash
            if (args.length == 2 && MD5_PATTERN.matcher(args[1]).matches()) {
                String hash = args[1].toLowerCase();
                FakeDonationRequest request = pendingRequests.get(hash);

                if (request != null && request.playerUuid().equals(player.getUniqueId())) {
                    pendingRequests.remove(hash);
                    executeFakeDonation(player, request.amount(), request.email(), request.method(), request.message());
                    return true;
                } else {
                    Map<String, String> p = new HashMap<>();
                    p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                    p.put("{COMMAND}", label);
                    player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
                    return true;
                }
            }

            if (args.length < 4) {
                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                p.put("{COMMAND}", label + " fakedonate <amount> <email> <method> [msg]");
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-usage", "{PREFIX} <red>Invalid usage. <reset>try to run <yellow>/donate help<reset> for help"), p));
                return true;
            }

            double amount;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("general-error", "{PREFIX} <red>Something wrong with the donation system! please contact admin"), p));
                return true;
            }

            String email = args[2];
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-email", "{PREFIX} <white>Please <red>provide <white>a valid email <gray>example: (your@gmail.com)"), p));
                return true;
            }

            String method = args[3].toLowerCase();
            if (!method.equals("qris") && !method.equals("gopay") && !method.equals("paypal")) {
                Map<String, String> p = new HashMap<>();
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                player.sendMessage(plugin.parseMessage("{PREFIX} <red>Invalid payment method! <yellow>Options: qris, gopay, paypal", p));
                return true;
            }

            String message = "";
            if (args.length > 4) {
                StringBuilder sb = new StringBuilder();
                for (int i = 4; i < args.length; i++) {
                    sb.append(args[i]).append(" ");
                }
                message = sb.toString().trim();
            }

            if (plugin.getConfig().getBoolean("donate.confirmation", true)) {
                if (plugin.getBedrockFormHandler() != null && plugin.getConfig().getBoolean("bedrock-support", false) && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                    plugin.getBedrockFormHandler().sendConfirmationForm(player, amount, email, method, message, true);
                    return true;
                }

                // Generate MD5 Hash
                long timestamp = System.currentTimeMillis();
                String rawString = player.getUniqueId().toString() + "-" + timestamp + "-" + amount + "-" + email + "-" + method;
                String hash = md5(rawString);

                if (hash == null) {
                    plugin.sendLangMessage(player, "general-error");
                    return true;
                }

                // Clear old fakedonate requests from this admin
                pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(player.getUniqueId()));
                pendingRequests.put(hash, new FakeDonationRequest(player.getUniqueId(), amount, email, method, message));

                Map<String, String> p = plugin.getDonationPlaceholders(player.getName(), amount, email, method, message);
                p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                p.put("{COMMAND}", "/pdn fakedonate " + hash);

                plugin.sendLangMessageList(player, "donation-confirmation-java", p);
                plugin.playConfigSounds(player, "sound-effects.donation-confirmation");
            } else {
                executeFakeDonation(player, amount, email, method, message);
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

    private void displayLeaderboard(CommandSender sender) {
        List<OverlayManager.LeaderboardEntry> entries = plugin.getOverlayManager().getCachedLeaderboard();
        Map<String, String> p = new HashMap<>();
        p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
        
        sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("leaderboard-header", "<gray>------ <gold>Donation Leaderboard <gray>------"), p));
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("leaderboard-empty", "<gray>No donation records found."), p));
        } else {
            for (int i = 0; i < entries.size(); i++) {
                OverlayManager.LeaderboardEntry entry = entries.get(i);
                Map<String, String> entryP = new HashMap<>(p);
                entryP.put("{RANK}", String.valueOf(i + 1));
                entryP.put("{NAME}", entry.name());
                entryP.put("{AMOUNT}", entry.amount());
                entryP.put("{AMOUNT_FORMATTED}", entry.amountFormatted());
                sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("leaderboard-format", "<yellow>{RANK}. <white>{NAME} <gray>- <green>Rp{AMOUNT_FORMATTED}"), entryP));
            }
        }
        sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("leaderboard-footer", "<gray>----------------------------"), p));
    }

    private void displayMilestone(CommandSender sender) {
        OverlayManager.MilestoneData data = plugin.getOverlayManager().getCachedMilestone();
        Map<String, String> p = new HashMap<>();
        p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
        
        sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("milestone-header", "<gray>------ <gold>Donation Milestone <gray>------"), p));
        if (data == null) {
            sender.sendMessage(plugin.parseMessage("<red>No milestone data available yet. Please wait or use --update.", p));
        } else {
            Map<String, String> dataP = new HashMap<>(p);
            dataP.put("{TITLE}", data.title());
            dataP.put("{CURRENT}", data.current());
            dataP.put("{TARGET}", data.target());
            dataP.put("{CURRENT_FORMATTED}", data.currentFormatted());
            dataP.put("{TARGET_FORMATTED}", data.targetFormatted());
            dataP.put("{PERCENT}", String.format("%.1f", data.getPercentage()));
            dataP.put("{START_DATE}", data.startDate());
            dataP.put("{START_TIME}", data.startTime());
            
            sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("milestone-title", "  <white>Target: <yellow>{TITLE}"), dataP));
            sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("milestone-progress", "  <white>Progress: <green>Rp{CURRENT_FORMATTED} <gray>/ <red>Rp{TARGET_FORMATTED}"), dataP));
            sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("milestone-percentage", "  <white>Percentage: <aqua>{PERCENT}%"), dataP));
        }
        sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("milestone-footer", "<gray>----------------------------"), p));
    }

    private void executeFakeDonation(Player player, double amount, String email, String method, String message) {
        String formattedAmount = plugin.formatIndonesianNumber(amount);
        String txId = "FAKETX-" + System.currentTimeMillis();

        if (plugin.getConfig().getBoolean("donate.notification", true)) {
            Map<String, String> p = plugin.getDonationPlaceholders(player.getName(), amount, email, method, message);
            p.put("{ID}", txId);
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));

            plugin.sendLangMessageList(Bukkit.getConsoleSender(), "donation-notification", p);
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                plugin.sendLangMessageList(onlinePlayer, "donation-notification", p);
                plugin.playConfigSounds(onlinePlayer, "sound-effects.donation-received");
            }
        }

        if (plugin.getTriggersManager() != null) {
            plugin.getTriggersManager().processDonation(player.getName(), amount, formattedAmount, message, method, txId);
        }
        
        // Update Overlay Cache on fake donation if configured
        if (plugin.getOverlayManager() != null && plugin.getOverlayManager().isConfigured()) {
            plugin.getOverlayManager().updateCacheAsync();
        }

        Map<String, String> fP = new HashMap<>();
        fP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
        player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("fake-donation-triggered", "{PREFIX} <green>Fake donation successfully triggered!</green>"), fP));
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            BigInteger no = new BigInteger(1, messageDigest);
            String hashtext = no.toString(16);
            while (hashtext.length() < 32) {
                hashtext = "0" + hashtext;
            }
            return hashtext;
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("MD5 algorithm not found!");
            return null;
        }
    }

    public void clearPendingRequests(UUID uuid) {
        pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(uuid));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            if ("fakedonate".startsWith(sub) && sender.hasPermission("plsdonate.admin")) completions.add("fakedonate");
            if ("reload".startsWith(sub) && sender.hasPermission("plsdonate.admin")) completions.add("reload");
            if ("leaderboard".startsWith(sub)) completions.add("leaderboard");
            if ("milestone".startsWith(sub)) completions.add("milestone");
            if ("help".startsWith(sub)) completions.add("help");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("leaderboard") || args[0].equalsIgnoreCase("milestone"))) {
            if ("--update".startsWith(args[1].toLowerCase())) completions.add("--update");
        } else if (args.length > 1 && args[0].equalsIgnoreCase("fakedonate") && sender.hasPermission("plsdonate.admin")) {
            if (args.length == 2) {
                String sub = args[1].toLowerCase();
                // Check if there are MD5 completions? Probably not needed as they are transient hashes
                if ("amount".startsWith(sub)) completions.add("amount");
                double min = plugin.getConfig().getDouble("donate.amount.min", 1000);
                long[] suggestions = { (long) min, (long) min + 5000, (long) min + 10000 };
                for (long s : suggestions) {
                    if (String.valueOf(s).startsWith(sub)) completions.add(String.valueOf(s));
                }
            } else if (args.length == 3) {
                String sub = args[2].toLowerCase();
                List<String> emails = plugin.getLangConfig().getStringList("donation-tab-completions-args2");
                if (emails.isEmpty()) emails = List.of("test@gmail.com");
                for (String e : emails) {
                    if (e.toLowerCase().startsWith(sub)) completions.add(e);
                }
            } else if (args.length == 4) {
                String sub = args[3].toLowerCase();
                List<String> methods = List.of("qris", "gopay", "paypal");
                for (String m : methods) {
                    if (m.startsWith(sub)) completions.add(m);
                }
            } else if (args.length == 5) {
                String sub = args[4].toLowerCase();
                List<String> msgs = plugin.getLangConfig().getStringList("donation-tab-completions-args3");
                if (msgs.isEmpty()) msgs = List.of("Fake_Donation");
                for (String m : msgs) {
                    if (m.toLowerCase().startsWith(sub)) completions.add(m);
                }
            }
        }
        return completions;
    }
}
