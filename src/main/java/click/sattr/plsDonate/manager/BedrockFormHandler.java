package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class BedrockFormHandler {

    private final PlsDonate plugin;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$");

    public BedrockFormHandler(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrockPlayer(Player player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    public void openDonationForm(Player player) {
        String amountMode = plugin.getConfig().getString("donation-form.amount-mode", "TEXT").toUpperCase();

        List<Integer> amountPresetsRaw = plugin.getConfig().getIntegerList("donation-form.amount-presets");
        if (amountPresetsRaw.isEmpty()) {
            amountPresetsRaw = List.of(5000, 10000, 25000, 50000, 100000, 250000, 500000);
        }
        final List<Integer> amountPresets = amountPresetsRaw.stream().sorted().collect(java.util.stream.Collectors.toList());

        int sliderMin = plugin.getConfig().getInt("donation-form.amount-slider-min", 1000);
        int sliderMax = plugin.getConfig().getInt("donation-form.amount-slider-max", 10000000);
        int sliderStep = plugin.getConfig().getInt("donation-form.amount-slider-step", 5000);
        int sliderDefault = plugin.getConfig().getInt("donation-form.amount-slider-default", 50000);

        List<Map<?, ?>> paymentMethodsRaw = plugin.getConfig().getMapList("donation-form.payment-methods");
        final List<String> methodIds;
        final List<Integer> methodMins;

        if (paymentMethodsRaw.isEmpty()) {
            methodIds = List.of("qris", "gopay", "paypal");
            methodMins = List.of(5000, 10000, 50000);
        } else {
            List<String> ids = new ArrayList<>();
            List<Integer> mins = new ArrayList<>();
            for (Map<?, ?> entry : paymentMethodsRaw) {
                String id = String.valueOf(entry.get("id")).toLowerCase();
                int min = 0;
                Object minObj = entry.get("min");
                if (minObj instanceof Number) {
                    min = ((Number) minObj).intValue();
                }
                ids.add(id);
                mins.add(min);
            }
            methodIds = java.util.Collections.unmodifiableList(ids);
            methodMins = java.util.Collections.unmodifiableList(mins);
        }

        List<String> methodDisplayNames = new ArrayList<>();
        for (String id : methodIds) {
            String label = plugin.getLangConfig().getString("donation-form.method-" + id, id.toUpperCase());
            methodDisplayNames.add(label);
        }

        String title = plugin.getLangConfig().getString("donation-form.title", "Donation Form");
        String amountLabel = plugin.getLangConfig().getString("donation-form.amount-label", "Amount");
        String amountPlaceholder = plugin.getLangConfig().getString("donation-form.amount-placeholder", "Enter amount...");
        String emailLabel = plugin.getLangConfig().getString("donation-form.email-label", "Email");
        String emailPlaceholder = plugin.getLangConfig().getString("donation-form.email-placeholder", "your@email.com");
        String methodLabel = plugin.getLangConfig().getString("donation-form.method-label", "Payment Method");
        String messageLabel = plugin.getLangConfig().getString("donation-form.message-label", "Message (optional)");
        String messagePlaceholder = plugin.getLangConfig().getString("donation-form.message-placeholder", "Your message...");

        CustomForm.Builder builder = CustomForm.builder().title(title);

        switch (amountMode) {
            case "DROPDOWN":
                List<String> presetLabels = new ArrayList<>();
                for (Integer p : amountPresets) {
                    presetLabels.add(MessageUtils.formatAmount(plugin, p.doubleValue()));
                }
                builder.dropdown(amountLabel, presetLabels);
                break;
            case "SLIDER":
                builder.slider(amountLabel, sliderMin, sliderMax, sliderStep, sliderDefault);
                break;
            default:
                builder.input(amountLabel, amountPlaceholder, "");
                break;
        }
        builder.input(emailLabel, emailPlaceholder, "");
        builder.dropdown(methodLabel, methodDisplayNames);
        builder.input(messageLabel, messagePlaceholder, "");

        CustomForm form = builder.validResultHandler((ff, response) -> {
            final double amount;
            switch (amountMode) {
                case "DROPDOWN": {
                    int idx = response.asDropdown(0);
                    amount = amountPresets.get(idx);
                    break;
                }
                case "SLIDER": {
                    amount = response.asSlider(0);
                    break;
                }
                default: {
                    String raw = response.asInput(0);
                    try {
                        amount = Double.parseDouble(raw);
                        if (!Double.isFinite(amount)) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Map<String, String> p = new HashMap<>();
                            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                            player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-amount", "{PREFIX} <white>Please <red>enter a valid amount <white>using numbers only <gray>(example: 50000)"), p));
                        });
                        return;
                    }
                    break;
                }
            }

            String email = response.asInput(1);
            int methodIdx = response.asDropdown(2);
            String formMessage = response.asInput(3);

            String method = methodIds.get(methodIdx);
            int methodMin = methodMins.get(methodIdx);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!EMAIL_PATTERN.matcher(email).matches() || email.length() > 64) {
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("invalid-email", "{PREFIX} <white>Please <red>provide <white>a valid email <gray>example: (your@gmail.com)"), p));
                    return;
                }

                double minConfig = plugin.getConfig().getDouble(Constants.CONF_DONATE_MIN_AMOUNT, 1000);
                double maxConfig = plugin.getConfig().getDouble(Constants.CONF_DONATE_MAX_AMOUNT, 10000000);

                if (amount < minConfig) {
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, minConfig));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("min-donation-error", "{PREFIX} <white>Sorry, <red>minimum <white>amount of donation is <yellow>Rp{AMOUNT_FORMATTED}"), p));
                    return;
                }
                if (amount > maxConfig) {
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, maxConfig));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("max-donation-error", "{PREFIX} <white>Sorry, <red>maximum <white>amount of donation is <yellow>Rp{AMOUNT_FORMATTED}"), p));
                    return;
                }

                if (amount < methodMin) {
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put(Constants.METHOD, method);
                    p.put("{METHOD_UPPERCASED}", method.toUpperCase());
                    p.put(Constants.AMOUNT_FORMATTED, MessageUtils.formatAmount(plugin, (double) methodMin));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("payment-method-min-error", "{PREFIX} <red>Minimum donation for {METHOD_UPPERCASED} is <yellow>Rp{AMOUNT_FORMATTED}"), p));
                    return;
                }

                int configMaxMsgLen = plugin.getConfig().getInt(Constants.CONF_DONATE_MAX_MESSAGE, 255);
                int platformMaxMsgLen = plugin.getDonationPlatform().getMaxMessageLength();
                int maxMsgLen = Math.min(Math.min(configMaxMsgLen, platformMaxMsgLen), 190);

                if (formMessage.length() > maxMsgLen) {
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put("{LIMIT}", String.valueOf(maxMsgLen));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("message-length-error", "{PREFIX} <white>Sorry, <red>maximal length <white>of the message is <yellow>{LIMIT} Character. <white>Please shorten your message."), p));
                    return;
                }

                sendConfirmationForm(player, amount, email, method, formMessage, false, false);
            });
        }).build();

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }

    public void sendConfirmationForm(Player player, double amount, String email, String method, String message, boolean isSimulation, boolean isSandbox) {
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
                 "<gray>Amount: <green>{AMOUNT_FORMATTED}",
                 "<gray>Email: <white>{EMAIL}",
                 "<gray>Method: <yellow>{METHOD}",
                 "<gray>Message: <yellow>{MESSAGE}"
             );
        }

        Map<String, String> p = MessageUtils.getDonationPlaceholders(plugin, amount, player.getName(), email, method, message);

        for (String line : rawContent) {
             String processedLine = line;
             for (Map.Entry<String, String> entry : p.entrySet()) {
                 processedLine = processedLine.replace(entry.getKey(), entry.getValue());
             }
             
             net.kyori.adventure.text.Component component = MessageUtils.parseMessage(processedLine);
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
                    if (isSimulation) {
                        processSimulatedDonation(player, amount, email, method, message, isSandbox);
                    } else {
                        processBedrockDonation(player, amount, email, method, message);
                    }
                }
            })
            .build();
        
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        
        // Sound: donation-confirmation
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
    }

    private void processSimulatedDonation(Player player, double amount, String email, String method, String message, boolean isSandbox) {
        String txId = (isSandbox ? "FAKETX-" : "PUSHTX-") + System.currentTimeMillis();

        plugin.getDonationService().fulfillDonation(player.getName(), amount, email, method, message, txId, isSandbox);

        Map<String, String> fpP = new HashMap<>();
        fpP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        String langKey = isSandbox ? "fake-donation-processed" : "push-donation-processed";
        String defaultMsg = isSandbox ? "{PREFIX} <green>Fake donation form successfully processed!</green>" : "{PREFIX} <green>Donation form successfully pushed!</green>";
        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString(langKey, defaultMsg), fpP));
    }

    private void processBedrockDonation(Player player, double amount, String email, String method, String message) {
        // Sound: donation-processed
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-processed");
        
        Map<String, String> prcP = new HashMap<>();
        prcP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-processing", "{PREFIX} <gray>Processing your donation form... Please check your email shortly!</gray>"), prcP));

        plugin.getDonationPlatform().createDonation(player.getName(), email, amount, method, message).thenAccept(response -> {
            if (response.success()) {
                // Log request to ledger
                if (response.transactionId() != null) {
                    plugin.getTransactionRepository().createDonationRequest(response.transactionId(), amount, player.getName(), false);
                }

                // Send Email to Bedrock Player
                plugin.getEmailManager().sendPaymentEmail(
                        player.getName(), 
                        email, 
                        amount, 
                        MessageUtils.formatAmount(plugin, amount), 
                        method,
                        response.paymentUrl(),
                        message
                );

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> succP = new HashMap<>();
                    succP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("donation-email-sent", "{PREFIX} <green>A payment link has been sent to your email!</green>"), succP));
                    // Sound: donation-success
                    MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-success");
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> errP = new HashMap<>();
                    errP.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    errP.put(Constants.ERROR, response.message());
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("api-error", "{PREFIX} <red>API Error: {ERROR}</red>"), errP));
                });
            }
        });
    }

    public void sendClearConfirmationForm(Player player, String target) {
        String title = plugin.getLangConfig().getString("transaction-clear-confirmation-bedrock.title", "Confirm Clear Transactions");
        String btnYes = plugin.getLangConfig().getString("transaction-clear-confirmation-bedrock.btn-yes", "Yes, Clear");
        String btnNo = plugin.getLangConfig().getString("transaction-clear-confirmation-bedrock.btn-no", "Cancel");

        StringBuilder contentInfo = new StringBuilder();
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacySection = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        List<String> rawContent = plugin.getLangConfig().getStringList("transaction-clear-confirmation-bedrock.content");
        if (rawContent.isEmpty()) {
             rawContent = List.of(
                 "§cAre you sure you want to clear transactions for: §f" + target + "?",
                 "",
                 "§7This action cannot be undone."
             );
        }

        for (String line : rawContent) {
             String processedLine = line.replace("{TARGET}", target);
             net.kyori.adventure.text.Component component = MessageUtils.parseMessage(processedLine);
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
                    plugin.getTransactionRepository().clearTransactions(target);
                    Map<String, String> p = new HashMap<>();
                    p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                    p.put("{TARGET}", target);
                    player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-cleared", "{PREFIX} <green>Cleared transactions for: {TARGET}"), p));
                }
            })
            .build();
        
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
    }

    public void sendDeleteConfirmationForm(Player player, int id) {
        String title = plugin.getLangConfig().getString("transaction-delete-confirmation-bedrock.title", "Confirm Delete Transaction");
        String btnYes = plugin.getLangConfig().getString("transaction-delete-confirmation-bedrock.btn-yes", "Yes, Delete");
        String btnNo = plugin.getLangConfig().getString("transaction-delete-confirmation-bedrock.btn-no", "Cancel");

        StringBuilder contentInfo = new StringBuilder();
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacySection = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        List<String> rawContent = plugin.getLangConfig().getStringList("transaction-delete-confirmation-bedrock.content");
        if (rawContent.isEmpty()) {
             rawContent = List.of(
                 "§cAre you sure you want to delete transaction §f#" + id + "?",
                 "",
                 "§7This action cannot be undone."
             );
        }

        for (String line : rawContent) {
             String processedLine = line.replace("{ID}", String.valueOf(id));
             net.kyori.adventure.text.Component component = MessageUtils.parseMessage(processedLine);
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
                    if (plugin.getTransactionRepository().deleteTransaction(id)) {
                        Map<String, String> p = new HashMap<>();
                        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                        p.put(Constants.ID, String.valueOf(id));
                        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-deleted", "{PREFIX} <green>Transaction #{ID} deleted."), p));
                    } else {
                        Map<String, String> p = new HashMap<>();
                        p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));
                        player.sendMessage(MessageUtils.parseMessage(plugin.getLangConfig().getString("transaction-not-found", "{PREFIX} <red>Transaction not found."), p));
                    }
                }
            })
            .build();
        
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        MessageUtils.playConfigSounds(player, plugin, "sound-effects.donation-confirmation");
    }
}
