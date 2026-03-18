package click.sattr.plsDonate;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public class StorageManager {
    private final PlsDonate plugin;
    private final File storageFile;
    private FileConfiguration storageConfig;
    
    private final File donationsFile;
    private FileConfiguration donationsConfig;

    public StorageManager(PlsDonate plugin) {
        this.plugin = plugin;
        File dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        
        this.storageFile = new File(dataDir, "offline_triggers.yml");
        this.donationsFile = new File(dataDir, "donations.yml");
        
        loadConfigs();
    }

    private void loadConfigs() {
        // Handle offline_triggers.yml
        if (!storageFile.exists()) {
            plugin.saveResource("data/offline_triggers.yml", false);
        }
        storageConfig = YamlConfiguration.loadConfiguration(storageFile);

        // Handle donations.yml
        if (!donationsFile.exists()) {
            plugin.saveResource("data/donations.yml", false);
        }
        donationsConfig = YamlConfiguration.loadConfiguration(donationsFile);
    }

    private void saveConfig() {
        try {
            storageConfig.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save offline_triggers.yml: " + e.getMessage());
        }
    }

    private void saveDonations() {
        try {
            donationsConfig.save(donationsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save donations.yml: " + e.getMessage());
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

    // Donation Ledger Methods
    
    public synchronized void createDonationRequest(String txId, double amount, String name) {
        String path = "transactions." + txId;
        donationsConfig.set(path + ".checksum", calculateMD5(txId + amount + name));
        donationsConfig.set(path + ".timestamp", System.currentTimeMillis() / 1000L);
        donationsConfig.set(path + ".status", "PENDING");
        saveDonations();
    }

    public synchronized boolean isTransactionValid(String txId, double amount, String name) {
        String path = "transactions." + txId;
        if (!donationsConfig.contains(path)) return false;
        
        String status = donationsConfig.getString(path + ".status", "PENDING");
        if (!"PENDING".equals(status)) return false;

        String storedChecksum = donationsConfig.getString(path + ".checksum");
        String currentChecksum = calculateMD5(txId + amount + name);
        
        return currentChecksum.equals(storedChecksum);
    }

    public synchronized void markTransactionUsed(String txId) {
        String path = "transactions." + txId;
        donationsConfig.set(path + ".status", "COMPLETED");
        donationsConfig.set(path + ".completed_at", System.currentTimeMillis() / 1000L);
        saveDonations();
    }

    private String calculateMD5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            BigInteger no = new BigInteger(1, messageDigest);
            String hashtext = no.toString(16);
            while (hashtext.length() < 32) {
                hashtext = "0" + hashtext;
            }
            return hashtext;
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("MD5 algorithm not found!");
            return null;
        }
    }
}
