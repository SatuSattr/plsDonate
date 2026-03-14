package click.sattr.plsDonate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.component.InputComponent;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BedrockFormHandler {

    private final PlsDonate plugin;

    public BedrockFormHandler(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrockPlayer(Player player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    private String formatBedrockString(String text, double min) {
        if (text == null || text.isBlank()) return text;
        String formatted = text
            .replace("{MIN}", String.valueOf((long) min))
            .replace("{MIN_FORMATTED}", plugin.formatIndonesianNumber(min));
        net.kyori.adventure.text.Component component = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(formatted);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component);
    }

    public void openDonateForm(Player player) {
        openDonateForm(player, null, "", "", "", false);
    }

    public void openFakeDonateForm(Player player) {
        openDonateForm(player, null, "", "", "", true);
    }

    public void openDonateForm(Player player, String errorMessage, String defaultAmount, String defaultEmail, String defaultMessage, boolean isFake) {
        FloodgatePlayer fPlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (fPlayer == null) return;

        double min = plugin.getConfig().getDouble("donate.amount.min", 10000);

        String title = formatBedrockString(plugin.getLangConfig().getString("donation-form-bedrock.title", "Support Our Server"), min);
        String errorPrefix = formatBedrockString(plugin.getLangConfig().getString("donation-form-bedrock.error-prefix", "<red><bold>Error: <reset><red>{ERROR}"), min);
        String amountLabel = formatBedrockString(plugin.getLangConfig().getString("donation-form-bedrock.amount.label", "Amount (Min: {MIN_FORMATTED})"), min);
        String amountPlaceholder = formatBedrockString(plugin.getLangConfig().getString("donation-form-bedrock.amount.placeholder", "e.g. 50000"), min);
        String emailLabel = formatBedrockString(plugin.getLangConfig().getString("donation-form-bedrock.email.label", "Email"), min);
        String emailPlaceholder = formatBedrockString(plugin.getLangConfig().getString("donation-form-bedrock.email.placeholder", "e.g. email@gmail.com"), min);
        String messageLabel = formatBedrockString(plugin.getLangConfig().getString("donation-form-bedrock.message.label", "Message (Optional)"), min);
        String messagePlaceholder = formatBedrockString(plugin.getLangConfig().getString("donation-form-bedrock.message.placeholder", "Type a message here"), min);

        CustomForm.Builder formBuilder = CustomForm.builder()
                .title(title);

        if (errorMessage != null && !errorMessage.isEmpty()) {
            formBuilder.component(org.geysermc.cumulus.component.LabelComponent.of(errorPrefix.replace("{ERROR}", errorMessage)));
        }

        formBuilder.component(InputComponent.of(amountLabel, amountPlaceholder, defaultAmount))
                .component(InputComponent.of(emailLabel, emailPlaceholder, defaultEmail))
                .component(InputComponent.of(messageLabel, messagePlaceholder, defaultMessage))
                .validResultHandler(response -> {
                    // Calculate offset if errorMessage label is present
                    int offset = (errorMessage != null && !errorMessage.isEmpty()) ? 1 : 0;
                    
                    String amountStr = response.asInput(offset);
                    String email = response.asInput(offset + 1);
                    String message = response.asInput(offset + 2);

                    if (amountStr == null || amountStr.isBlank() || email == null || email.isBlank()) {
                        openDonateForm(player, "Amount and Email are required!", amountStr, email, message, isFake);
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        processBedrockDonation(player, amountStr, email, message, isFake);
                    });
                });

        fPlayer.sendForm(formBuilder.build());
    }

    private void processBedrockDonation(Player player, String amountStr, String email, String message, boolean isFake) {
        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            openDonateForm(player, "Invalid amount format! Only numbers allowed.", amountStr, email, message, isFake);
            return;
        }

        double minConfig = plugin.getConfig().getDouble("donate.amount.min", 10000);
        double maxConfig = plugin.getConfig().getDouble("donate.amount.max", 10000000);

        if (amount < minConfig) {
            openDonateForm(player, "Minimum donation is Rp" + plugin.formatIndonesianNumber(minConfig) + "!", amountStr, email, message, isFake);
            return;
        }

        if (amount > maxConfig) {
            openDonateForm(player, "Maximum donation is Rp" + plugin.formatIndonesianNumber(maxConfig) + "!", amountStr, email, message, isFake);
            return;
        }

        if (!email.matches("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) {
            openDonateForm(player, "Invalid email format!", amountStr, email, message, isFake);
            return;
        }

        int configMaxMsgLen = plugin.getConfig().getInt("donate.message.max-length", 255);
        int platformMaxMsgLen = plugin.getDonationPlatform().getMaxMessageLength();
        int maxMsgLen = Math.min(configMaxMsgLen, platformMaxMsgLen);
        
        String finalMessage = (message == null) ? "" : message.trim();
        
        if (finalMessage.length() > maxMsgLen) {
            openDonateForm(player, "Message is too long (Max: " + maxMsgLen + ")!", amountStr, email, message, isFake);
            return;
        }

        if (plugin.getConfig().getBoolean("donate.confirmation", true)) {
            sendConfirmationForm(player, amount, email, finalMessage, isFake);
        } else {
            processBedrockDonation(player, amount, email, finalMessage, isFake);
        }
    }

    public void sendConfirmationForm(Player player, double amount, String email, String message, boolean isFake) {
        String title = plugin.getLangConfig().getString("donation-confirmation-bedrock.title", "Confirm Donation");
        String btnYes = plugin.getLangConfig().getString("donation-confirmation-bedrock.btn-yes", "Yes");
        String btnNo = plugin.getLangConfig().getString("donation-confirmation-bedrock.btn-no", "No");

        StringBuilder contentInfo = new StringBuilder();
        net.kyori.adventure.text.minimessage.MiniMessage miniMessage = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacySection = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        List<String> rawContent = plugin.getLangConfig().getStringList("donation-confirmation-bedrock.content");
        if (rawContent.isEmpty()) {
             rawContent = List.of(
                 "<gray>Are you sure you want to donate?", "",
                 "<gray>Amount: <green>Rp{AMOUNT_FORMATTED}",
                 "<gray>Email: <white>{EMAIL}",
                 "<gray>Message: <yellow>{MESSAGE}"
             );
        }

        for (String line : rawContent) {
             String formatted = line
                     .replace("{AMOUNT}", String.valueOf((long) amount))
                     .replace("{AMOUNT_FORMATTED}", plugin.formatIndonesianNumber(amount))
                     .replace("{EMAIL}", email != null ? email : "")
                     .replace("{MESSAGE}", message != null && !message.isEmpty() ? message : "No message");
             
             net.kyori.adventure.text.Component component = miniMessage.deserialize(formatted);
             String legacyText = legacySection.serialize(component);
             contentInfo.append(legacyText).append("\n");
        }

        SimpleForm form = SimpleForm.builder()
            .title(title)
            .content(contentInfo.toString())
            .button(btnYes)
            .button(btnNo)
            .validResultHandler(response -> {
                if (response.clickedButtonId() == 0) {
                    processBedrockDonation(player, amount, email, message, isFake);
                }
            })
            .build();
        
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        
        // Sound: donation-confirmation
        plugin.playConfigSounds(player, "sound-effects.donation-confirmation");
    }

    private void processBedrockDonation(Player player, double amount, String email, String message, boolean isFake) {
        if (isFake) {
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

                Map<String, String> fpP = new HashMap<>();
                fpP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("fake-donation-processed", "{PREFIX} <green>Fake donation form successfully processed!</green>"), fpP));
                return;
        }

        // Sound: donation-processed
        plugin.playConfigSounds(player, "sound-effects.donation-processed");
        
        Map<String, String> prcP = new HashMap<>();
        prcP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
        player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("donation-processing", "{PREFIX} <gray>Processing your donation form... Please check your email shortly!</gray>"), prcP));

        plugin.getDonationPlatform().createDonation(player.getName(), email, amount, "qris", message).thenAccept(response -> {
            if (response.success()) {
                // Update Database
                plugin.getDatabaseManager().insertDonation(
                        player.getName(), email, amount, message, "qris", response.transactionId(), response.paymentUrl()
                );

                // Send Email to Bedrock Player
                plugin.getEmailManager().sendPaymentEmail(
                        player.getName(), 
                        email, 
                        amount, 
                        plugin.formatIndonesianNumber(amount), 
                        response.paymentUrl(),
                        message
                );

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> succP = new HashMap<>();
                    succP.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));
                    player.sendMessage(plugin.parseMessage(plugin.getLangConfig().getString("donation-email-sent", "{PREFIX} <green>A payment link has been sent to your email!</green>"), succP));
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
}
