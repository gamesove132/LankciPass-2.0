package world.lankci.pass.quest;

import java.util.Locale;

public enum QuestKind {
    DAILY("daily"),
    MEGA("mega");

    private final String key;

    QuestKind(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static QuestKind parse(String s) {
        if (s == null) {
            return null;
        }
        String l = s.toLowerCase(Locale.ROOT);
        for (QuestKind k : values()) {
            if (k.key.equals(l) || k.name().equalsIgnoreCase(l)) {
                return k;
            }
        }
        return null;
    }
}
