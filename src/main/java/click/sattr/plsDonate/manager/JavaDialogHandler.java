package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import io.github.projectunified.unidialog.paper.PaperDialogManager;
import io.github.projectunified.unidialog.paper.dialog.PaperConfirmationDialog;
import io.github.projectunified.unidialog.paper.dialog.PaperMultiActionDialog;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class JavaDialogHandler {

    private final PlsDonate plugin;
    private final PaperDialogManager dialogManager;

    public JavaDialogHandler(PlsDonate plugin) {
        this.plugin = plugin;
        this.dialogManager = new PaperDialogManager(plugin);
    }

    /**
     * Returns true if this server supports Java Edition custom dialogs (1.21.6+).
     * Checks for the Paper dialog API class and verifies the server version string.
     */
    public static boolean isServerSupported() {
        try {
            Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            String version = org.bukkit.Bukkit.getServer().getBukkitVersion();
            // version string like "1.21.6-R0.1-SNAPSHOT" or "1.22.0-R0.1-SNAPSHOT"
            // Extract major.minor.patch from the version string
            String[] parts = version.split("[.-]");
            if (parts.length < 2) return false;
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
            // Require 1.21.6 or higher
            if (major > 1) return true;
            if (major < 1) return false;
            if (minor > 21) return true;
            if (minor < 21) return false;
            return patch >= 6;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Opens the multi-field donation form dialog for a Java Edition player.
     *
     * @return true if the dialog was opened successfully, false on error
     */
    public boolean openDonationForm(Player player) {
        try {
            final List<String> methodIds = List.of("qris", "gopay", "paypal");

            Component title = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.title", "Donation"));
            Component amountLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.amount-label", "Amount"));
            Component emailLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.email-label", "Email"));
            Component methodLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.method-label", "Payment Method"));
            Component messageLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.message-label", "Message (optional)"));

            Component submitLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.submit-label", "Submit"));
            Component cancelLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.cancel-label", "Cancel"));

            PaperMultiActionDialog dialog = dialogManager.createMultiActionDialog()
                    .title(title)
                    .input("amount", b -> b.textInput()
                            .label(amountLabel)
                            .maxLength(10))
                    .input("email", b -> b.textInput()
                            .label(emailLabel)
                            .maxLength(100))
                    .input("method", b -> {
                        var soi = b.singleOptionInput().label(methodLabel);
                        boolean first = true;
                        for (String id : methodIds) {
                            Component displayName = MessageUtils.parseMessage(
                                    plugin.getLangConfig().getString("donation-form-java.method-" + id, id.toUpperCase()));
                            soi.option(id, displayName, first);
                            first = false;
                        }
                    })
                    .input("message", b -> b.textInput()
                            .label(messageLabel)
                            .maxLength(100))
                    // Submit button — fires "/donate $(amount) $(email) $(method) $(message)"
                    .action(a -> a
                            .label(submitLabel)
                            .dynamicRunCommand("donate $(amount) $(email) $(method) $(message)"))
                    // Cancel button — runs empty command (effectively does nothing / closes dialog)
                    .action(a -> a
                            .label(cancelLabel)
                            .runCommand(""));

            dialog.opener().open(player);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("JavaDialogHandler: failed to open donation form for "
                    + player.getName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Opens a confirmation dialog for a pending donation.
     *
     * @return true if the dialog was opened successfully, false on error
     */
    public boolean openConfirmationDialog(Player player, String hash, double amount,
                                          String email, String method, String message) {
        try {
            Component title = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.confirm-title", "Confirm Donation"));

            // Build body text from donation placeholders
            Map<String, String> p = MessageUtils.getDonationPlaceholders(
                    plugin, amount, player.getName(), email, method, message);
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));

            String bodyTemplate = plugin.getLangConfig().getString(
                    "donation-confirmation-java-dialog",
                    "<white>Amount: <yellow>{AMOUNT_FORMATTED}\n<white>Email: <yellow>{EMAIL}\n<white>Method: <yellow>{METHOD}\n<white>Message: <gray>{MESSAGE}");
            String bodyText = bodyTemplate;
            for (Map.Entry<String, String> entry : p.entrySet()) {
                bodyText = bodyText.replace(entry.getKey(), entry.getValue());
            }
            Component bodyComponent = MessageUtils.parseMessage(bodyText);

            Component yesLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.confirm-yes-label", "Confirm"));
            Component noLabel = MessageUtils.parseMessage(
                    plugin.getLangConfig().getString("donation-form-java.confirm-no-label", "Cancel"));

            final String confirmCommand = "donate " + hash;

            PaperConfirmationDialog dialog = dialogManager.createConfirmationDialog()
                    .title(title)
                    .body(b -> b.text().text(bodyComponent))
                    .yesAction(a -> a
                            .label(yesLabel)
                            .runCommand(confirmCommand))
                    .noAction(a -> a
                            .label(noLabel));

            dialog.opener().open(player);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("JavaDialogHandler: failed to open confirmation dialog for "
                    + player.getName() + ": " + t.getMessage());
            return false;
        }
    }
}
