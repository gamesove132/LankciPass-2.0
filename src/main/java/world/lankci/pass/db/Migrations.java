package world.lankci.pass.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * Версіонування схеми через PRAGMA user_version. Кожна міграція виконується в транзакції.
 * Щоб додати зміну схеми: додайте новий елемент у STEPS (не змінюйте старі!) - LATEST збільшиться автоматично.
 */
public final class Migrations {
    private Migrations() {
    }

    private static final List<String[]> STEPS = List.<String[]>of(
            // v1 - початкова схема LankciPass 2.0
            new String[]{
                    """
                    CREATE TABLE players (
                      uuid TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      tokens INTEGER NOT NULL DEFAULT 0 CHECK (tokens >= 0),
                      total_earned INTEGER NOT NULL DEFAULT 0,
                      legacy_imported INTEGER NOT NULL DEFAULT 0,
                      first_seen INTEGER NOT NULL,
                      last_seen INTEGER NOT NULL
                    )""",
                    """
                    CREATE TABLE season_defs (
                      season_id TEXT PRIMARY KEY,
                      archived INTEGER NOT NULL DEFAULT 0,
                      created_at INTEGER NOT NULL
                    )""",
                    """
                    CREATE TABLE season_runs (
                      run_id INTEGER PRIMARY KEY AUTOINCREMENT,
                      season_id TEXT NOT NULL,
                      status TEXT NOT NULL CHECK (status IN ('SCHEDULED','ACTIVE','ENDED')),
                      start_at INTEGER NOT NULL,
                      end_at INTEGER NOT NULL,
                      duration_days INTEGER NOT NULL,
                      progress_mode TEXT NOT NULL,
                      claim_policy TEXT NOT NULL DEFAULT 'ALLOW',
                      created_at INTEGER NOT NULL,
                      ended_at INTEGER,
                      end_reason TEXT,
                      remaining_ms INTEGER,
                      restore_count INTEGER NOT NULL DEFAULT 0
                    )""",
                    "CREATE UNIQUE INDEX idx_one_active_run ON season_runs(status) WHERE status = 'ACTIVE'",
                    "CREATE INDEX idx_runs_season ON season_runs(season_id)",
                    """
                    CREATE TABLE run_players (
                      run_id INTEGER NOT NULL,
                      uuid TEXT NOT NULL,
                      points INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (run_id, uuid)
                    )""",
                    """
                    CREATE TABLE reward_claims (
                      run_id INTEGER NOT NULL,
                      uuid TEXT NOT NULL,
                      level INTEGER NOT NULL,
                      season_id TEXT NOT NULL,
                      claimed_at INTEGER NOT NULL,
                      source TEXT NOT NULL DEFAULT 'GUI',
                      PRIMARY KEY (run_id, uuid, level)
                    )""",
                    "CREATE INDEX idx_claims_season ON reward_claims(season_id, uuid, level)",
                    """
                    CREATE TABLE quest_cycles (
                      cycle_id INTEGER PRIMARY KEY AUTOINCREMENT,
                      mode TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      start_at INTEGER NOT NULL,
                      end_at INTEGER NOT NULL,
                      quest_ids TEXT NOT NULL
                    )""",
                    "CREATE INDEX idx_cycles_mode_kind ON quest_cycles(mode, kind, cycle_id)",
                    """
                    CREATE TABLE quest_progress (
                      uuid TEXT NOT NULL,
                      cycle_id INTEGER NOT NULL,
                      quest_id TEXT NOT NULL,
                      progress INTEGER NOT NULL DEFAULT 0,
                      completed INTEGER NOT NULL DEFAULT 0,
                      claimed INTEGER NOT NULL DEFAULT 0,
                      completed_at INTEGER,
                      claimed_at INTEGER,
                      extra TEXT,
                      PRIMARY KEY (uuid, cycle_id, quest_id)
                    )""",
                    """
                    CREATE TABLE pvp_log (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      event_key TEXT NOT NULL UNIQUE,
                      killer TEXT NOT NULL,
                      victim TEXT NOT NULL,
                      mode TEXT,
                      tokens INTEGER NOT NULL,
                      at INTEGER NOT NULL
                    )""",
                    "CREATE INDEX idx_pvp_pair ON pvp_log(killer, victim, at)",
                    "CREATE INDEX idx_pvp_killer ON pvp_log(killer, at)",
                    """
                    CREATE TABLE world_bindings (
                      world_uuid TEXT PRIMARY KEY,
                      world_name TEXT NOT NULL,
                      mode TEXT NOT NULL,
                      updated_at INTEGER NOT NULL
                    )""",
                    """
                    CREATE TABLE token_log (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      uuid TEXT NOT NULL,
                      delta INTEGER NOT NULL,
                      balance INTEGER NOT NULL,
                      reason TEXT NOT NULL,
                      at INTEGER NOT NULL
                    )""",
                    "CREATE INDEX idx_token_log_uuid ON token_log(uuid, at)"
            }
    );

    public static final int LATEST = STEPS.size();

    public static int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); var rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public static void apply(Connection c, int from, Logger log) throws SQLException {
        if (from > LATEST) {
            throw new SQLException("База даних новіша (v" + from + ") за версію плагіна (v" + LATEST
                    + "). Оновіть плагін або відновіть резервну копію.");
        }
        for (int v = from; v < LATEST; v++) {
            log.info("Міграція бази даних: v" + v + " -> v" + (v + 1));
            boolean oldAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String sql : STEPS.get(v)) {
                    st.execute(sql);
                }
                st.execute("PRAGMA user_version = " + (v + 1));
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(oldAuto);
            }
        }
    }
}
