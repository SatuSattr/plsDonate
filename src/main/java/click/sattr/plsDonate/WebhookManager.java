package click.sattr.plsDonate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class WebhookManager {

    private final PlsDonate plugin;
    private HttpServer server;

    public WebhookManager(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public boolean start(int port, String path) {
        if (server != null) {
            stop();
        }

        try {
            // Ensure path starts with / for HttpServer
            String formattedPath = path.startsWith("/") ? path : "/" + path;
            
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext(formattedPath, new WebhookHandler());
            server.setExecutor(null); // creates a default executor
            server.start();
            plugin.getLogger().info("Webhook listener started on port " + port + " at path " + formattedPath);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start webhook listener on port " + port);
            e.printStackTrace();
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            plugin.getLogger().info("Webhook listener stopped.");
        }
    }

    private class WebhookHandler implements HttpHandler {
        private static final int MAX_BODY_SIZE = 3072; // 3KB limit

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "Method Not Allowed");
                    return;
                }

                // Read body with size limit
                String body;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[MAX_BODY_SIZE + 1];
                    int read = reader.read(buffer, 0, MAX_BODY_SIZE + 1);
                    if (read > MAX_BODY_SIZE) {
                        sendResponse(exchange, 413, "Payload Too Large");
                        return;
                    }
                    body = new String(buffer, 0, read);
                }

                // Call active platform to parse and verify the webhook
                click.sattr.plsDonate.platform.DonationPlatform.WebhookResult result = plugin.getDonationPlatform().parseWebhook(body, exchange.getRequestHeaders());

                if (!result.valid()) {
                    plugin.getLogger().warning("Webhook Validation Failed: " + result.errorMessage());
                    sendResponse(exchange, 400, "Bad Request");
                    return;
                }

                // Success - Verification Passed
                plugin.getLogger().info("Received verified webhook for tx: " + result.transactionId());

                String transactionId = result.transactionId();

                // 1. Database Match Check & Update
                if (plugin.getDatabaseManager().checkTransactionExists(transactionId)) {
                    plugin.getDatabaseManager().updateDonationStatus(transactionId, "paid");
                    plugin.getLogger().info("Successfully processed internal donation: " + transactionId);
                } else {
                    plugin.getLogger().info("Received valid external/test webhook (ID: " + transactionId + "). Not in database, but will broadcast.");
                    logFailedWebhookRaw(body, new GsonBuilder().setPrettyPrinting().create());
                }

                // 2. Global Broadcast Notification (Always if webhook is valid)
                if (plugin.getConfig().getBoolean("donate.notification", true)) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        java.util.Map<String, String> p = new java.util.HashMap<>();

                        p.put("{PLAYER}", result.donorName());
                        p.put("{AMOUNT}", String.valueOf((long)result.amount()));
                        p.put("{AMOUNT_FORMATTED}", plugin.formatIndonesianNumber(result.amount()));
                        p.put("{MESSAGE}", result.message());
                        p.put("{EMAIL}", result.donorEmail());
                        p.put("{METHOD}", result.paymentMethod());
                        p.put("{ID}", transactionId);
                        p.put("{PREFIX}", plugin.getLangConfig().getString("prefix", "[plsDonate]"));

                        plugin.sendLangMessageList(Bukkit.getConsoleSender(), "donation-notification", p);
                        for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                            plugin.sendLangMessageList(onlinePlayer, "donation-notification", p);
                            plugin.playConfigSounds(onlinePlayer, "sound-effects.donation-received");
                        }

                        if (plugin.getTriggersManager() != null) {
                            plugin.getTriggersManager().processDonation(
                                result.donorName(),
                                result.amount(),
                                plugin.formatIndonesianNumber(result.amount()),
                                result.message(),
                                result.paymentMethod(),
                                transactionId
                            );
                        }
                    });
                }

                sendResponse(exchange, 200, "OK");

            } catch (Exception e) {
                plugin.getLogger().severe("Error handling webhook request.");
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error");
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.sendResponseHeaders(statusCode, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }

        private void logFailedWebhookRaw(String rawBody, Gson gson) {
            File logFile = new File(plugin.getDataFolder(), "failed-webhook.json");
            JsonArray array = new JsonArray();

            JsonObject payload = null;
            try {
                payload = new JsonParser().parse(rawBody).getAsJsonObject();
            } catch (Exception ignored) {}

            if (payload == null) return;

            if (logFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                    array = new JsonParser().parse(reader).getAsJsonArray();
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not read existing failed-webhook.json. Creating new array.");
                }
            } else {
                try {
                    logFile.getParentFile().mkdirs();
                    logFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().severe("Could not create failed-webhook.json: " + e.getMessage());
                    return;
                }
            }

            array.add(payload);

            try (FileWriter writer = new FileWriter(logFile)) {
                gson.toJson(array, writer);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to write to failed-webhook.json: " + e.getMessage());
            }
        }
    }
}
