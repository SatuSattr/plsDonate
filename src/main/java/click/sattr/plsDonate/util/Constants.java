package click.sattr.plsDonate.util;

public final class Constants {

    private Constants() {} // Prevent instantiation

    // Placeholders
    public static final String PREFIX = "{PREFIX}";
    public static final String PLAYER = "{PLAYER}";
    public static final String AMOUNT = "{AMOUNT}";
    public static final String AMOUNT_FORMATTED = "{AMOUNT_FORMATTED}";
    public static final String EMAIL = "{EMAIL}";
    public static final String METHOD = "{METHOD}";
    public static final String MESSAGE = "{MESSAGE}";
    public static final String ID = "{ID}";
    public static final String COMMAND = "{COMMAND}";
    public static final String RANK = "{RANK}";
    public static final String TARGET = "{TARGET}";
    public static final String PERCENTAGE = "{PERCENT}";
    public static final String BAR = "{BAR}";
    public static final String TITLE = "{TITLE}";
    public static final String ERROR = "{ERROR}";
    public static final String TIME = "{TIME}";

    // Permissions
    public static final String PERM_ADMIN = "plsdonate.admin";
    public static final String PERM_COOLDOWN_BYPASS = "plsdonate.cooldown.bypass";

    // Configuration Paths
    public static final String CONF_WEBHOOK_PORT = "webhook.port";
    public static final String CONF_WEBHOOK_PATH = "webhook.path";
    public static final String CONF_TAKO_TOKEN = "tako.webhook-token";
    public static final String CONF_TAKO_CREATOR = "tako.creator";
    public static final String CONF_TAKO_KEY = "tako.api-key";
    public static final String CONF_DONATE_NOTIFICATION = "donate.notification";
    public static final String CONF_DONATE_CONFIRMATION = "donate.confirmation";
    public static final String CONF_DONATE_COOLDOWN = "donate.cooldown";
    public static final String CONF_DONATE_MIN_AMOUNT = "donate.amount.min";
    public static final String CONF_DONATE_MAX_AMOUNT = "donate.amount.max";
    public static final String CONF_DONATE_MAX_MESSAGE = "donate.message.max-length";
    public static final String CONF_MILESTONE_ENABLED = "tako.milestone.enabled";
    public static final String CONF_MILESTONE_OFFSET = "tako.milestone.start-offset";
    public static final String CONF_MILESTONE_TARGET = "tako.milestone.target";
    public static final String CONF_MILESTONE_TITLE = "tako.milestone.title";
    public static final String CONF_BEDROCK_SUPPORT = "bedrock-support";
    
    // Formatting Configuration
    public static final String CONF_FORMAT_LOCALE = "format.locale";

    // Defaults
    public static final String DEFAULT_PREFIX = "<gray>[<green>plsDonate<gray>]<reset>";
    public static final int DEFAULT_WEBHOOK_PORT = 21172;
    public static final String DEFAULT_WEBHOOK_PATH = "plsdonate";
}
