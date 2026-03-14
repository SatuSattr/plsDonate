package click.sattr.plsDonate;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private final PlsDonate plugin;
    private Connection connection;

    public DatabaseManager(PlsDonate plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dbFile = new File(dataFolder, "donations.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(url);
            plugin.getLogger().info("Successfully connected to local SQLite database.");
            return initTable();
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Failed to connect to SQLite database: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean initTable() {
        String sql = "CREATE TABLE IF NOT EXISTS donations (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, email TEXT NOT NULL, amount NUMERIC NOT NULL, message TEXT, paymentMethod TEXT NOT NULL, transactionId TEXT NOT NULL UNIQUE, paymentUrl TEXT NOT NULL, status TEXT NOT NULL CHECK (status IN ('pending', 'unpaid', 'paid', 'expired')), paid_at TEXT, created_at TEXT NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%SZ', 'now')));";
        String queueSql = "CREATE TABLE IF NOT EXISTS offline_triggers (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, command TEXT NOT NULL);";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            stmt.execute(queueSql);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public synchronized boolean insertDonation(String player, String email, double amount, String message, String paymentMethod, String transactionId, String paymentUrl) {
        if (connection == null) return false;

        String sql = "INSERT INTO donations (player, email, amount, message, paymentMethod, transactionId, paymentUrl, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'unpaid')";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, player);
            pstmt.setString(2, email);
            pstmt.setDouble(3, amount);
            pstmt.setString(4, message);
            pstmt.setString(5, paymentMethod);
            pstmt.setString(6, transactionId);
            pstmt.setString(7, paymentUrl);
            
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to insert donation into database: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public synchronized boolean checkTransactionExists(String transactionId) {
        if (connection == null) return false;
        String sql = "SELECT id FROM donations WHERE transactionId = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, transactionId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next(); // true if at least one row exists
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to check transaction existence: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public synchronized boolean updateDonationStatus(String transactionId, String status) {
        if (connection == null) return false;
        String sql;
        if ("paid".equalsIgnoreCase(status)) {
            sql = "UPDATE donations SET status = ?, paid_at = STRFTIME('%Y-%m-%dT%H:%M:%SZ', 'now') WHERE transactionId = ?";
        } else {
            sql = "UPDATE donations SET status = ? WHERE transactionId = ?";
        }

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setString(2, transactionId);
            int rowsAffected = pstmt.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to update transaction status: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }



    public synchronized DonationRecord getDonationRecord(String transactionId) {
        if (connection == null) return null;
        String sql = "SELECT player, email, amount, message, paymentMethod, transactionId FROM donations WHERE transactionId = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, transactionId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new DonationRecord(
                        rs.getString("player"),
                        rs.getString("email"),
                        rs.getDouble("amount"),
                        rs.getString("message"),
                        rs.getString("paymentMethod"),
                        rs.getString("transactionId")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to fetch donation record: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public record DonationRecord(String player, String email, double amount, String message, String paymentMethod, String transactionId) {}

    public synchronized boolean insertOfflineTrigger(String player, String command) {
        if (connection == null) return false;
        String sql = "INSERT INTO offline_triggers (player, command) VALUES (?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, player.toLowerCase());
            pstmt.setString(2, command);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to insert offline trigger: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public synchronized List<String> getAndRemoveOfflineTriggers(String player) {
        List<String> commands = new ArrayList<>();
        if (connection == null) return commands;
        
        String selectSql = "SELECT id, command FROM offline_triggers WHERE player = ?";
        String deleteSql = "DELETE FROM offline_triggers WHERE id = ?";
        
        try (PreparedStatement selectPstmt = connection.prepareStatement(selectSql)) {
            selectPstmt.setString(1, player.toLowerCase());
            try (ResultSet rs = selectPstmt.executeQuery()) {
                while (rs.next()) {
                    commands.add(rs.getString("command"));
                    try (PreparedStatement deletePstmt = connection.prepareStatement(deleteSql)) {
                        deletePstmt.setInt(1, rs.getInt("id"));
                        deletePstmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to process offline triggers: " + e.getMessage());
            e.printStackTrace();
        }
        return commands;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("SQLite connection closed.");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to close SQLite connection.");
            e.printStackTrace();
        }
    }
}
