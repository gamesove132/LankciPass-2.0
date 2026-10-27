package world.lankci.pass.season;

import org.bukkit.Material;

import java.util.List;

/** Опис сезону з seasons.yml (оформлення, рівні, нагороди). Стан запусків зберігається в SQLite. */
public record SeasonDef(String id, int number, String name, String theme, String color, Material icon,
                        int durationDays, boolean legacyImport, List<LevelDef> levels) {

    public LevelDef level(int number) {
        for (LevelDef l : levels) {
            if (l.number() == number) {
                return l;
            }
        }
        return null;
    }

    /** Назва з кольором, напр. "&b Сезон 1". */
    public String coloredName() {
        return color + name;
    }
}
