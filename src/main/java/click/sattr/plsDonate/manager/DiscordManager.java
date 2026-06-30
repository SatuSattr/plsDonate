package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.PlsDonate;
import click.sattr.plsDonate.util.MessageUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Sends a customizable Discord embed via webhook URL when a live donation is fulfilled.
 *
 * <p>Two distinct injection surfaces are handled separately. Text fields (title, description,
 * fields, footer) are emitted through Gson's {@link JsonObject}, so any quotes or braces a donor
 * types survive as escaped DATA and can never break out into new JSON keys. URL fields (thumbnail,
 * author/footer icon) percent-encode every player-derived value before substitution, so a donor who
 * renames themselves into something like {@code x/../?to=evil} can only fill a single path segment —
 * they cannot inject a new host or scheme. {@code {PLAYER_HEAD}} is pre-encoded and substituted
 * verbatim so it is never double-encoded.
 */
public class DiscordManager {

    private static final String HEAD_URL_BASE = "https://mc-heads.net/avatar/";
    private static final String EMBED_BASE = "discord.embed.";

    private final PlsDonate plugin;
    private final HttpClient httpClient;

    public DiscordManager(PlsDonate plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record EmbedField(String name, String value, boolean inline) {}

    public record EmbedSpec(String color, String authorName, String authorIconUrl, String title,
                            String description, String thumbnail, List<EmbedField> fields,
                            String footerText, String footerIconUrl, boolean timestamp) {}

    private record Ctx(String headUrl, String player, String message, String amount,
                       String amountFormatted, String method, String id, String date) {}

    public void sendDonation(String playerName, double amount, String message, String method, String txId) {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return;
        dispatch(playerName, amount, message, method, txId);
    }

    /**
     * Sends a sample embed using the admin's configured layout, bypassing the
     * {@code discord.enabled} flag so the setup can be verified before going live.
     * Returns the number of valid webhooks dispatched to.
     */
    public int sendTest(String playerName) {
        return dispatch(playerName, 50000, "This is a test donation message!", "qris",
                "TEST-" + System.currentTimeMillis());
    }

    private int dispatch(String playerName, double amount, String message, String method, String txId) {
        List<String> webhooks = plugin.getConfig().getStringList("discord.webhooks");
        if (webhooks.isEmpty()) return 0;

        EmbedSpec spec = readSpec();
        String amountStr = String.valueOf((long) amount);
        String amountFormatted = MessageUtils.formatAmount(plugin, amount);
        String friendlyMethod = MessageUtils.friendlyMethod(plugin.getLangConfig(), method);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String msg = (message == null) ? "" : message;

        String payload = buildPayload(spec, playerName, msg, amountStr, amountFormatted,
                friendlyMethod, txId, date, Instant.now());

        int sent = 0;
        for (String url : webhooks) {
            if (url == null || url.isBlank() || !url.startsWith("http")) continue;
            send(url, payload);
            sent++;
        }
        return sent;
    }

    private EmbedSpec readSpec() {
        FileConfiguration c = plugin.getConfig();
        List<EmbedField> fields = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList(EMBED_BASE + "fields")) {
            Object n = m.get("name");
            Object v = m.get("value");
            Object inl = m.get("inline");
            String name = n == null ? "" : String.valueOf(n);
            String value = v == null ? "" : String.valueOf(v);
            boolean inline = inl instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(inl));
            fields.add(new EmbedField(name, value, inline));
        }

        return new EmbedSpec(
                c.getString(EMBED_BASE + "color", ""),
                c.getString(EMBED_BASE + "author.name", ""),
                c.getString(EMBED_BASE + "author.icon-url", ""),
                c.getString(EMBED_BASE + "title", ""),
                c.getString(EMBED_BASE + "description", ""),
                c.getString(EMBED_BASE + "thumbnail", ""),
                fields,
                c.getString(EMBED_BASE + "footer.text", ""),
                c.getString(EMBED_BASE + "footer.icon-url", ""),
                c.getBoolean(EMBED_BASE + "timestamp", true));
    }

    public static String buildPayload(EmbedSpec spec, String player, String message, String amount,
                                      String amountFormatted, String method, String id, String date,
                                      Instant timestamp) {
        Ctx c = new Ctx(HEAD_URL_BASE + enc(player), player, message, amount, amountFormatted, method, id, date);

        JsonObject embed = new JsonObject();

        Integer color = parseColor(spec.color());
        if (color != null) embed.addProperty("color", color);

        String title = resolveText(spec.title(), c);
        if (!title.isEmpty()) embed.addProperty("title", title);

        String description = resolveText(spec.description(), c);
        if (!description.isEmpty()) embed.addProperty("description", description);

        String authorName = resolveText(spec.authorName(), c);
        if (!authorName.isEmpty()) {
            JsonObject author = new JsonObject();
            author.addProperty("name", authorName);
            String icon = resolveUrl(spec.authorIconUrl(), c);
            if (isHttpUrl(icon)) author.addProperty("icon_url", icon);
            embed.add("author", author);
        }

        String thumb = resolveUrl(spec.thumbnail(), c);
        if (isHttpUrl(thumb)) {
            JsonObject t = new JsonObject();
            t.addProperty("url", thumb);
            embed.add("thumbnail", t);
        }

        JsonArray fieldsArr = new JsonArray();
        for (EmbedField f : spec.fields()) {
            String value = resolveText(f.value(), c);
            if (value.isEmpty()) continue;
            JsonObject fo = new JsonObject();
            fo.addProperty("name", resolveText(f.name(), c));
            fo.addProperty("value", value);
            fo.addProperty("inline", f.inline());
            fieldsArr.add(fo);
        }
        if (!fieldsArr.isEmpty()) embed.add("fields", fieldsArr);

        String footerText = resolveText(spec.footerText(), c);
        if (!footerText.isEmpty()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", footerText);
            String icon = resolveUrl(spec.footerIconUrl(), c);
            if (isHttpUrl(icon)) footer.addProperty("icon_url", icon);
            embed.add("footer", footer);
        }

        if (spec.timestamp()) embed.addProperty("timestamp", timestamp.toString());

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject root = new JsonObject();
        root.add("embeds", embeds);

        // Never let donor text ping the server: no content body, and disable all mention parsing.
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        root.add("allowed_mentions", allowedMentions);

        return root.toString();
    }

    private static String resolveText(String template, Ctx c) {
        if (template == null) return "";
        return template
                .replace("{PLAYER_HEAD}", c.headUrl())
                .replace("{PLAYER}", c.player())
                .replace("{AMOUNT_FORMATTED}", c.amountFormatted())
                .replace("{AMOUNT}", c.amount())
                .replace("{MESSAGE}", c.message())
                .replace("{METHOD}", c.method())
                .replace("{ID}", c.id())
                .replace("{DATE}", c.date());
    }

    private static String resolveUrl(String template, Ctx c) {
        if (template == null) return "";
        return template
                .replace("{PLAYER_HEAD}", c.headUrl())
                .replace("{PLAYER}", enc(c.player()))
                .replace("{AMOUNT_FORMATTED}", enc(c.amountFormatted()))
                .replace("{AMOUNT}", enc(c.amount()))
                .replace("{MESSAGE}", enc(c.message()))
                .replace("{METHOD}", enc(c.method()))
                .replace("{ID}", enc(c.id()))
                .replace("{DATE}", enc(c.date()));
    }

    /** RFC 3986 path-segment encoding: form-encode then turn '+' into %20 so spaces aren't '+'. */
    private static String enc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static Integer parseColor(String hex) {
        if (hex == null) return null;
        hex = hex.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.isEmpty()) return null;
        try {
            int v = Integer.parseInt(hex, 16);
            if (v < 0 || v > 0xFFFFFF) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void send(String url, String payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        int sc = response.statusCode();
                        if (sc < 200 || sc >= 300) {
                            plugin.getLogger().warning("Discord webhook returned HTTP " + sc + ": " + response.body());
                        }
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Failed to send Discord webhook: " + ex.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid Discord webhook URL configured: " + e.getMessage());
        }
    }
}
