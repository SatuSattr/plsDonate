package click.sattr.plsDonate;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class StorageManager {
    private final PlsDonate plugin;
    private final File storageFile;
    private FileConfiguration storageConfig;

    public StorageManager(PlsDonate plugin) {
        this.plugin = plugin;
        // Pindah ke folder /data/
        File dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        this.storageFile = new File(dataDir, "offline_triggers.yml");
        loadConfig();
    }

    private void loadConfig() {
        if (!storageFile.exists()) {
            try {
                storageFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create offline_triggers.yml: " + e.getMessage());
            }
        }
        storageConfig = YamlConfiguration.loadConfiguration(storageFile);
        
        // Tambahkan header peringatan agar tidak diubah manual
        storageConfig.options().header(
            "=======================================================================\n" +
            "                           OFFLINE TRIGGERS DATA                         \n" +
            "=======================================================================\n" +
            " WARNING: DO NOT MODIFY THIS FILE MANUALLY!                            \n" +
            " This file is used internally by the plugin to store rewards for       \n" +
            " offline players. Manual changes can cause data corruption or          \n" +
            " lead to reward fulfillment failures.                                  \n" +
            "======================================================================="
        );
        storageConfig.options().copyHeader(true);
        saveConfig();
    }

    private void saveConfig() {
        try {
            storageConfig.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save offline_triggers.yml: " + e.getMessage());
        }
    }

    public synchronized void insertOfflineTrigger(String player, String command) {
        String path = "triggers." + player.toLowerCase();
        List<String> commands = storageConfig.getStringList(path);
        commands.add(command);
        storageConfig.set(path, commands);
        saveConfig();
    }

    public synchronized List<String> getAndRemoveOfflineTriggers(String player) {
        String path = "triggers." + player.toLowerCase();
        List<String> commands = storageConfig.getStringList(path);
        if (!commands.isEmpty()) {
            storageConfig.set(path, null);
            saveConfig();
        }
        return commands;
    }
}
