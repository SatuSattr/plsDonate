package click.sattr.plsDonate;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class CustomDonateCommand extends Command {

    private final PlsDonate plugin;
    // Map to store pending confirmations: Map<PlayerUUID, CommandString>
    private final Map<UUID, String> pendingConfirmations = new ConcurrentHashMap<>();
    
    // Simple Email Regex Pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$");

    protected CustomDonateCommand(@NotNull String name, PlsDonate plugin) {
        super(name, "Main donate command for plsDonate", "/" + name + " <amount> <email> [message]", List.of());
        this.plugin = plugin;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Map<String, String> pOnly = new HashMap<>();
            pOnly.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            sender.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("command-only-players", "{PREFIX} <red>Only players can execute this command.</red>"), pOnly));
            return true;
        }

        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {
            
            // Check Bedrock Form feature
            if (args.length == 0 && plugin.getBedrockFormHandler() != null && plugin.getConfig().getBoolean("bedrock-support", false)) {
                if (plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                    plugin.getBedrockFormHandler().openDonateForm(player);
                    return true;
                }
            }
            
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{COMMAND}", commandLabel);
            plugin.sendLangMessageList(player, "donation-help", p);
            return true;
        }

        if (args.length < 2) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{COMMAND}", commandLabel);
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-usage", "<red>Invalid usage. <reset>try to run <yellow>/{COMMAND} help<reset> for help"), p));
            return true;
        }

        // 1. Amount Validation
        double amount;
        try {
            amount = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("general-error", "<red>Invalid amount format."), p));
            return true;
        }

        double minConfig = plugin.getConfig().getDouble("donate.amount.min", 10000);
        double maxConfig = plugin.getConfig().getDouble("donate.amount.max", 10000000);

        if (amount < minConfig) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{AMOUNT}", String.valueOf((long) minConfig));
            p.put("{AMOUNT_FORMATTED}", plugin.formatIndonesianNumber(minConfig));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("min-donation-error", "<red>Minimum donation is {AMOUNT}"), p));
            return true;
        }

        if (amount > maxConfig) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{AMOUNT}", String.valueOf((long) maxConfig));
            p.put("{AMOUNT_FORMATTED}", plugin.formatIndonesianNumber(maxConfig));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("max-donation-error", "<red>Maximum donation is {AMOUNT}"), p));
            return true;
        }

        // 2. Email Validation
        String email = args[1];
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("invalid-email", "<red>Invalid email format."), p));
            return true;
        }

        // 3. Message Validation
        String message = "";
        if (args.length > 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                sb.append(args[i]).append(" ");
            }
            message = sb.toString().trim();
        }

        int configMaxMsgLen = plugin.getConfig().getInt("donate.message.max-length", 255);
        int platformMaxMsgLen = plugin.getDonationPlatform().getMaxMessageLength();
        int maxMsgLen = Math.min(configMaxMsgLen, platformMaxMsgLen);
        
        if (message.length() > maxMsgLen) {
            Map<String, String> p = new HashMap<>();
            p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
            p.put("{LIMIT}", String.valueOf(maxMsgLen));
            player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("message-length-error", "<red>Message too long. Limit is {LIMIT}"), p));
            return true;
        }

        // Reconstruct the full string they typed for confirmation matching
        StringBuilder fullCommandBuilder = new StringBuilder("/").append(commandLabel);
        for (String arg : args) {
            fullCommandBuilder.append(" ").append(arg);
        }
        String fullCommand = fullCommandBuilder.toString();

        UUID uuid = player.getUniqueId();

        // 4. Confirmation System
        boolean requireConfirmation = plugin.getConfig().getBoolean("donate.confirmation", true);

        if (!requireConfirmation || (pendingConfirmations.containsKey(uuid) && pendingConfirmations.get(uuid).equals(fullCommand))) {
            // CONFIRMED OR NOT REQUIRED! Process the API Call
            pendingConfirmations.remove(uuid);
            processDonation(player, amount, email, message, plugin);
        } else {
            if (plugin.getBedrockFormHandler() != null && plugin.getConfig().getBoolean("bedrock-support", false) && plugin.getBedrockFormHandler().isBedrockPlayer(player)) {
                plugin.getBedrockFormHandler().sendConfirmationForm(player, amount, email, message, false);
                return true;
            }

            // NOT CONFIRMED YET, Set it and prompt (Java)
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
            
            // Sound: donation-confirmation
            plugin.playConfigSounds(player, "sound-effects.donation-confirmation");
        }

        return true;
    }

    public static void processDonation(Player player, double amount, String email, String message, PlsDonate plugin) {
        Map<String, String> p = new HashMap<>();
        p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
        p.put("{AMOUNT}", String.valueOf((long) amount));
        p.put("{AMOUNT_FORMATTED}", plugin.formatIndonesianNumber(amount));

        // Sound: donation-processed
        plugin.playConfigSounds(player, "sound-effects.donation-processed");

        final String finalMsg = message;
        plugin.getDonationPlatform().createDonation(player.getName(), email, amount, "qris", finalMsg).thenAccept(response -> {
            if (response.success()) {
                // Update Database
                plugin.getDatabaseManager().insertDonation(
                        player.getName(), email, amount, finalMsg, "qris", response.transactionId(), response.paymentUrl()
                );

                // Send Success URL Message
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> succP = new HashMap<>();
                    succP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                    succP.put("{PAYMENT_URL}", response.paymentUrl());
                    plugin.sendLangMessageList(player, "donation-success-java", succP);
                    
                    // Send Email to Bedrock explicitly from here if form submitted instantly via Java CMD? 
                    // No, Bedrock players use BedrockFormHandler directly.
                    
                    // Sound: donation-success
                    plugin.playConfigSounds(player, "sound-effects.donation-success");
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> errP = new HashMap<>();
                    errP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                    errP.put("{ERROR}", response.message());
                    player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("api-error", "{PREFIX} <red>API Error: {ERROR}</red>"), errP));
                });
            }
        });
    }
    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            
            // 1. Help & Literal "amount"
            if ("help".startsWith(sub)) completions.add("help");
            if ("amount".startsWith(sub)) completions.add("amount");
            
            // 2. Calculated Amounts from config
            double min = plugin.getConfig().getDouble("donate.amount.min", 10000);
            long[] suggestions = {
                (long) min,
                (long) min + 3000,
                (long) min + 8000,
                (long) min + 15000
            };
            
            for (long s : suggestions) {
                String val = String.valueOf(s);
                if (val.startsWith(sub)) {
                    completions.add(val);
                }
            }
            return completions;
        }

        if (args.length == 2) {
            String sub = args[1].toLowerCase();
            List<String> args2 = plugin.getLangConfig().getStringList("donation-tab-completions-args2");
            if (args2.isEmpty()) args2 = List.of("your@email.com");
            
            for (String s : args2) {
                if (s.toLowerCase().startsWith(sub)) {
                    completions.add(s);
                }
            }
            return completions;
        }

        if (args.length == 3) {
            String sub = args[2].toLowerCase();
            List<String> args3 = plugin.getLangConfig().getStringList("donation-tab-completions-args3");
            if (args3.isEmpty()) args3 = List.of("message");

            for (String s : args3) {
                if (s.toLowerCase().startsWith(sub)) {
                    completions.add(s);
                }
            }
            return completions;
        }

        return super.tabComplete(sender, alias, args);
    }
}
