package world.lankci.pass.quest;

import java.util.List;

/** Цикл квестів (24 год для звичайних, 3 дні для мега) для одного режиму. */
public record Cycle(long id, String mode, QuestKind kind, long startAt, long endAt, List<String> questIds) {
    public long remaining(long now) {
        return Math.max(0, endAt - now);
    }
}
