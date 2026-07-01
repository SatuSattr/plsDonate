package click.sattr.plsDonate.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the display-indicator helpers resolve from the lang file when keys are present
 * and fall back to the built-in defaults when they are absent or the config is null.
 * Uses a real {@link YamlConfiguration} rather than a mock, matching the project's test style.
 */
class MessageUtilsTest {

    @Test
    void friendlyMethodDefaultsWhenNoLang() {
        assertEquals("QRIS", MessageUtils.friendlyMethod(null, null));
        assertEquals("QRIS", MessageUtils.friendlyMethod(null, "qris"));
        assertEquals("GoPay", MessageUtils.friendlyMethod(null, "GOPAY"));
        assertEquals("PayPal", MessageUtils.friendlyMethod(null, "paypal"));
        assertEquals("QRIS", MessageUtils.friendlyMethod(null, "weird"));
    }

    @Test
    void friendlyMethodReadsCustomLabels() {
        YamlConfiguration lang = new YamlConfiguration();
        lang.set("method-qris", "QR Pay");
        lang.set("method-gopay", "OVO");
        lang.set("method-paypal", "PP");

        assertEquals("QR Pay", MessageUtils.friendlyMethod(lang, "qris"));
        assertEquals("OVO", MessageUtils.friendlyMethod(lang, "gopay"));
        assertEquals("PP", MessageUtils.friendlyMethod(lang, "paypal"));
        assertEquals("QR Pay", MessageUtils.friendlyMethod(lang, "unknown-method"));
    }

    @Test
    void formatStatusDefaults() {
        assertEquals("<green>COMPLETED", MessageUtils.formatStatus(null, "COMPLETED"));
        assertEquals("<yellow>PENDING", MessageUtils.formatStatus(null, "PENDING"));
        assertEquals("<red>VOID", MessageUtils.formatStatus(null, "VOID"));
        // case-insensitive on the raw status
        assertEquals("<green>COMPLETED", MessageUtils.formatStatus(null, "completed"));
        // unknown status passes through unchanged
        assertEquals("WEIRD", MessageUtils.formatStatus(null, "WEIRD"));
    }

    @Test
    void formatStatusReadsCustomLabels() {
        YamlConfiguration lang = new YamlConfiguration();
        lang.set("status-completed", "<gold>PAID");
        lang.set("status-pending", "<gray>WAITING");

        assertEquals("<gold>PAID", MessageUtils.formatStatus(lang, "COMPLETED"));
        assertEquals("<gray>WAITING", MessageUtils.formatStatus(lang, "PENDING"));
        // key not overridden -> default
        assertEquals("<red>VOID", MessageUtils.formatStatus(lang, "VOID"));
    }

    @Test
    void formatTypeDefaultsAndCustom() {
        assertEquals("<red>SANDBOX", MessageUtils.formatType(null, true));
        assertEquals("<green>LIVE", MessageUtils.formatType(null, false));

        YamlConfiguration lang = new YamlConfiguration();
        lang.set("type-sandbox", "<dark_red>TEST");
        lang.set("type-live", "<aqua>REAL");
        assertEquals("<dark_red>TEST", MessageUtils.formatType(lang, true));
        assertEquals("<aqua>REAL", MessageUtils.formatType(lang, false));
    }
}
