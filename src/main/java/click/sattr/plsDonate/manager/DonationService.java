package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.Constants;
import click.sattr.plsDonate.util.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

public class DonationService {

    private final PlsDonate plugin;

    public DonationService(PlsDonate plugin) {
        this.plugin = plugin;
    }

    /**
     * Processes a donation (real or simulated).
     *
     * @param playerName The name of the player associated with the donation.
     * @param amount     The donation amount.
     * @param email      The donor's email.
     * @param method     The payment method used.
     * @param message    The donation message.
     * @param transactionId The unique transaction ID.
     * @param isSandbox  Whether the donation is in sandbox mode.
     */
    public void fulfillDonation(String playerName, double amount, String email, String method, String message, String transactionId, boolean isSandbox) {
        String formattedAmount = MessageUtils.formatIndonesianNumber(amount);

        // 1. Save to Database
        plugin.getTransactionRepository().createDonationRequest(transactionId, amount, playerName, isSandbox);
        plugin.getTransactionRepository().markTransactionUsed(transactionId);

        // 2. Broadcast Notifications
        if (plugin.getConfig().getBoolean(Constants.CONF_DONATE_NOTIFICATION, true)) {
            Map<String, String> p = MessageUtils.getDonationPlaceholders(amount, playerName, email, method, message);
            p.put(Constants.ID, transactionId);
            p.put(Constants.PREFIX, plugin.getLangConfig().getString("prefix", Constants.DEFAULT_PREFIX));

            MessageUtils.sendLangMessageList(Bukkit.getConsoleSender(), plugin, "donation-notification", p);
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                MessageUtils.sendLangMessageList(onlinePlayer, plugin, "donation-notification", p);
                MessageUtils.playConfigSounds(onlinePlayer, plugin, "sound-effects.donation-received");
            }
        }

        // 3. Trigger processing system
        if (plugin.getTriggersManager() != null) {
            plugin.getTriggersManager().processDonation(playerName, amount, formattedAmount, message, method, transactionId);
        }
    }
}
