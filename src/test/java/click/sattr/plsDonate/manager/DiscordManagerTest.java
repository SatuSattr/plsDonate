package click.sattr.plsDonate.manager;

import click.sattr.plsDonate.manager.DiscordManager.EmbedField;
import click.sattr.plsDonate.manager.DiscordManager.EmbedSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiscordManagerTest {

    private static final Instant TS = Instant.parse("2026-06-30T10:15:30Z");

    private static JsonObject embedOf(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return root.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    @Test
    void happyPathResolvesPlaceholdersAndStructure() {
        EmbedSpec spec = new EmbedSpec("#FFD700", "New Donation!", "{PLAYER_HEAD}", "",
                "**{PLAYER}** donated Rp{AMOUNT_FORMATTED}", "{PLAYER_HEAD}", "",
                List.of(), "plsDonate", "", true);

        String json = DiscordManager.buildPayload(spec, "Notch", null, "", "", "50000", "50.000",
                "QRIS", "TX1", "2026-06-30 10:15:30", TS);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(root.has("content"), "no content field -> no message body that could ping");
        assertEquals(0, root.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        JsonObject embed = root.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("**Notch** donated Rp50.000", embed.get("description").getAsString());
        assertEquals(16766720, embed.get("color").getAsInt());
        assertEquals("New Donation!", embed.getAsJsonObject("author").get("name").getAsString());
        assertEquals("https://vzge.me/face/256/Notch", embed.getAsJsonObject("author").get("icon_url").getAsString());
        assertEquals("https://vzge.me/face/256/Notch", embed.getAsJsonObject("thumbnail").get("url").getAsString());
        assertEquals("2026-06-30T10:15:30Z", embed.get("timestamp").getAsString());
        assertFalse(embed.has("title"), "empty title is omitted");
    }

    @Test
    void jsonInjectionInPlayerNameIsContained() {
        String evil = "\",\"description\":\"HACKED";
        EmbedSpec spec = new EmbedSpec("", "", "", "", "**{PLAYER}**", "", "",
                List.of(), "", "", false);

        String json = DiscordManager.buildPayload(spec, evil, null, "", "", "0", "0", "QRIS", "T", "d", TS);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        // No injected top-level keys: only what we put there.
        assertEquals(Set.of("embeds", "allowed_mentions"), root.keySet());

        JsonObject embed = root.getAsJsonArray("embeds").get(0).getAsJsonObject();
        // The malicious text survives intact AS DATA, escaped by Gson.
        assertEquals("**" + evil + "**", embed.get("description").getAsString());
        // No injected key leaked into the embed object either.
        assertEquals(Set.of("description"), embed.keySet());
    }

    @Test
    void playerValueInUrlFieldIsPercentEncoded() {
        EmbedSpec spec = new EmbedSpec("", "", "", "", "x", "https://skin.example/{PLAYER}", "",
                List.of(), "", "", false);

        String json = DiscordManager.buildPayload(spec, "evil/../x?a=b", null, "", "", "0", "0", "QRIS", "T", "d", TS);

        assertEquals("https://skin.example/evil%2F..%2Fx%3Fa%3Db",
                embedOf(json).getAsJsonObject("thumbnail").get("url").getAsString());
    }

    @Test
    void imageFieldRendersAndPercentEncodesPlayerValue() {
        EmbedSpec spec = new EmbedSpec("", "", "", "", "x", "", "https://banner.example/{PLAYER}",
                List.of(), "", "", false);

        String json = DiscordManager.buildPayload(spec, "evil/../x?a=b", null, "", "", "0", "0", "QRIS", "T", "d", TS);

        // image is a separate field from thumbnail and gets the same URL-encoding protection
        assertEquals("https://banner.example/evil%2F..%2Fx%3Fa%3Db",
                embedOf(json).getAsJsonObject("image").get("url").getAsString());
        assertFalse(embedOf(json).has("thumbnail"), "blank thumbnail stays omitted");
    }

    @Test
    void playerHeadUrlIsNotDoubleEncoded() {
        EmbedSpec spec = new EmbedSpec("", "", "", "", "x", "{PLAYER_HEAD}", "",
                List.of(), "", "", false);

        String json = DiscordManager.buildPayload(spec, "foo bar", null, "", "", "0", "0", "QRIS", "T", "d", TS);

        // space encoded exactly once -> %20, never %2520
        assertEquals("https://vzge.me/face/256/foo%20bar",
                embedOf(json).getAsJsonObject("thumbnail").get("url").getAsString());
    }

    @Test
    void emptyFieldValueIsSkipped() {
        List<EmbedField> fields = List.of(
                new EmbedField("Amount", "Rp{AMOUNT_FORMATTED}", true),
                new EmbedField("Message", "{MESSAGE}", false));
        EmbedSpec spec = new EmbedSpec("", "", "", "", "x", "", "", fields, "", "", false);

        String emptyMsg = DiscordManager.buildPayload(spec, "Notch", null, "", "", "0", "50.000", "QRIS", "T", "d", TS);
        assertEquals(1, embedOf(emptyMsg).getAsJsonArray("fields").size());
        assertEquals("Amount", embedOf(emptyMsg).getAsJsonArray("fields").get(0).getAsJsonObject().get("name").getAsString());

        String withMsg = DiscordManager.buildPayload(spec, "Notch", null, "", "gg", "0", "50.000", "QRIS", "T", "d", TS);
        assertEquals(2, embedOf(withMsg).getAsJsonArray("fields").size());
    }

    @Test
    void nonUrlIconValueIsOmitted() {
        EmbedSpec spec = new EmbedSpec("", "Author", "{MESSAGE}", "", "x", "", "",
                List.of(), "", "", false);

        // plain text message -> not a URL -> icon omitted, author kept
        String json = DiscordManager.buildPayload(spec, "Notch", null, "", "hello", "0", "0", "QRIS", "T", "d", TS);
        JsonObject author = embedOf(json).getAsJsonObject("author");
        assertEquals("Author", author.get("name").getAsString());
        assertFalse(author.has("icon_url"));

        // malicious url in message -> encoded so it no longer looks like a URL -> omitted
        String evil = DiscordManager.buildPayload(spec, "Notch", null, "", "https://evil.com/x.png", "0", "0", "QRIS", "T", "d", TS);
        assertFalse(embedOf(evil).getAsJsonObject("author").has("icon_url"));
    }

    @Test
    void invalidColorIsOmitted() {
        EmbedSpec spec = new EmbedSpec("notacolor", "", "", "", "x", "", "", List.of(), "", "", false);
        String json = DiscordManager.buildPayload(spec, "Notch", null, "", "", "0", "0", "QRIS", "T", "d", TS);
        assertFalse(embedOf(json).has("color"));
    }

    @Test
    void timestampDisabledIsOmitted() {
        EmbedSpec spec = new EmbedSpec("", "", "", "", "x", "", "", List.of(), "", "", false);
        String json = DiscordManager.buildPayload(spec, "Notch", null, "", "", "0", "0", "QRIS", "T", "d", TS);
        assertFalse(embedOf(json).has("timestamp"));
    }

    @Test
    void skinTextureIdPlaceholderReturnsRawHash() {
        String hash = "abc123def456";
        EmbedSpec spec = new EmbedSpec("", "", "", "", "{SKIN_TEXTURE_ID_SKIN_RESTORER}", "", "",
                List.of(), "", "", false);

        String json = DiscordManager.buildPayload(spec, "Notch", null, hash, "", "0", "0", "QRIS", "T", "d", TS);
        assertEquals(hash, embedOf(json).get("description").getAsString());
    }

    @Test
    void skinTextureIdEmptyWhenNoSkinsRestorer() {
        EmbedSpec spec = new EmbedSpec("", "", "", "", "{SKIN_TEXTURE_ID_SKIN_RESTORER}", "", "",
                List.of(), "", "", false);

        // null srTextureId (no SR installed) → empty string → description omitted
        String json = DiscordManager.buildPayload(spec, "Notch", null, null, "", "0", "0", "QRIS", "T", "d", TS);
        assertFalse(embedOf(json).has("description"), "empty SKIN_TEXTURE_ID_SKIN_RESTORER should produce empty description which is omitted");
    }

    @Test
    void skinTextureIdInUrlFieldIsPercentEncoded() {
        // texture IDs are hex hashes and won't contain special chars in practice,
        // but the enc() path should still be exercised
        String hash = "abc123def456";
        EmbedSpec spec = new EmbedSpec("", "", "", "", "x", "https://skin.example/{SKIN_TEXTURE_ID_SKIN_RESTORER}", "",
                List.of(), "", "", false);

        String json = DiscordManager.buildPayload(spec, "Notch", null, hash, "", "0", "0", "QRIS", "T", "d", TS);
        assertEquals("https://skin.example/abc123def456",
                embedOf(json).getAsJsonObject("thumbnail").get("url").getAsString());
    }
}
