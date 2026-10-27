package world.lankci.pass.season;

/**
 * Запуск сезону. Має унікальний runId; прогрес і отримані нагороди гравців прив'язані саме до runId,
 * тому різні запуски одного сезону ніколи не змішуються автоматично.
 *
 * @param progressMode INITIAL | NEW | RESET | RESTORE | SCHEDULED
 * @param claimPolicy  ALLOW - нагороди можна отримати заново; LOCK_PREVIOUS - нагороди, отримані в
 *                     попередніх запусках цього сезону, повторно не видаються
 */
public record SeasonRun(long runId, String seasonId, RunStatus status, long startAt, long endAt, int durationDays,
                        String progressMode, String claimPolicy, long createdAt, long endedAt, String endReason,
                        long remainingMs, int restoreCount) {

    public long remaining(long now) {
        return Math.max(0, endAt - now);
    }

    public boolean runningAt(long now) {
        return status == RunStatus.ACTIVE && now < endAt;
    }
}
