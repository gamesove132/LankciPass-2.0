package world.lankci.pass.util;

import world.lankci.pass.config.PluginSettings;

public final class TimeUtil {
    public static final long SECOND = 1000L;
    public static final long MINUTE = 60 * SECOND;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;

    private TimeUtil() {
    }

    /** "12д 04г 33хв", "05г 10хв 20с", "3хв 12с", "8с". */
    public static String duration(long ms) {
        if (ms <= 0) {
            return "0с";
        }
        long s = ms / 1000;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (d > 0) {
            return d + "д " + String.format("%02d", h) + "г " + String.format("%02d", m) + "хв";
        }
        if (h > 0) {
            return h + "г " + String.format("%02d", m) + "хв " + String.format("%02d", sec) + "с";
        }
        if (m > 0) {
            return m + "хв " + String.format("%02d", sec) + "с";
        }
        return sec + "с";
    }

    /** Кількість повних днів, що залишилися (округлення вгору не робимо). */
    public static long fullDays(long ms) {
        return Math.max(0, ms / DAY);
    }

    /**
     * Колір таймера сезону: &gt; warningDays - стандартний, &lt;= warningDays - жовтий,
     * &lt;= criticalDays - червоний. Рахується від реального залишку часу.
     */
    public static String timerColor(long remainingMs, PluginSettings s) {
        if (remainingMs <= s.criticalDays * DAY) {
            return s.colorCritical;
        }
        if (remainingMs <= s.warningDays * DAY) {
            return s.colorWarning;
        }
        return s.colorNormal;
    }
}
