package world.lankci.pass.token;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Кеш даних онлайн-гравців для PlaceholderAPI та GUI (джерело істини - SQLite). Лише читання з головного потоку. */
public final class PlayerCache {
    /** Прогрес одного квесту в поточному циклі. */
    public record QuestProg(long progress, boolean completed, boolean claimed) {
    }

    public static final class Stats {
        public volatile long tokens;
        public volatile long points;
        public volatile long runId = -1;
        public volatile Set<Integer> claimed = Set.of();
        /** Ключ: cycleId + ":" + questId. */
        public volatile Map<String, QuestProg> quests = Map.of();
        public volatile boolean loaded;
    }

    private final Map<UUID, Stats> map = new ConcurrentHashMap<>();

    public Stats get(UUID id) {
        return map.get(id);
    }

    public Stats getOrCreate(UUID id) {
        return map.computeIfAbsent(id, k -> new Stats());
    }

    public void set(UUID id, long tokens, long points) {
        Stats s = getOrCreate(id);
        s.tokens = tokens;
        s.points = points;
        s.loaded = true;
    }

    public void remove(UUID id) {
        map.remove(id);
    }

    public void clear() {
        map.clear();
    }

    public int size() {
        return map.size();
    }
}
