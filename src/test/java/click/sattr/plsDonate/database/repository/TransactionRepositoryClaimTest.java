package click.sattr.plsDonate.database.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for the atomic replay-claim semantics (H1). Uses a real temporary
 * SQLite database so the production claim query is exercised against the actual engine.
 */
class TransactionRepositoryClaimTest {

    private Path dbFile;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("plsdonate-claim-test", ".db");
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "tx_id TEXT UNIQUE, " +
                    "amount REAL, " +
                    "donor_name TEXT, " +
                    "checksum TEXT, " +
                    "status TEXT, " +
                    "timestamp INTEGER, " +
                    "completed_at INTEGER, " +
                    "is_sandbox INTEGER DEFAULT 0)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (dbFile != null) Files.deleteIfExists(dbFile);
    }

    private void insertPending(String txId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transactions (tx_id, amount, donor_name, status) VALUES (?, ?, ?, 'PENDING')")) {
            ps.setString(1, txId);
            ps.setDouble(2, 5000);
            ps.setString(3, "Steve");
            ps.executeUpdate();
        }
    }

    @Test
    void firstClaimOfPendingTransactionSucceeds() throws Exception {
        insertPending("tx-1");
        assertEquals(1, TransactionRepository.claimPending(conn, "tx-1"));
    }

    @Test
    void replayClaimOfAlreadyCompletedTransactionAffectsNoRows() throws Exception {
        insertPending("tx-1");
        assertEquals(1, TransactionRepository.claimPending(conn, "tx-1"), "first claim should win");
        assertEquals(0, TransactionRepository.claimPending(conn, "tx-1"), "replay must not be claimable again");
    }

    @Test
    void claimingUnknownTransactionAffectsNoRows() throws Exception {
        assertEquals(0, TransactionRepository.claimPending(conn, "does-not-exist"));
    }
}
