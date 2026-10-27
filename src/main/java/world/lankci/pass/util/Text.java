package world.lankci.pass.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** Допоміжні методи для тексту з кольоровими кодами (&amp; та &amp;#RRGGBB). */
public final class Text {
    private static final LegacyComponentSerializer AMP =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private Text() {
    }

    /** Компонент без курсиву за замовчуванням (для назв і lore предметів). */
    public static Component c(String s) {
        if (s == null) {
            s = "";
        }
        return Component.empty().decoration(TextDecoration.ITALIC, false).append(AMP.deserialize(s));
    }

    public static List<Component> lines(List<String> in) {
        List<Component> out = new ArrayList<>(in.size());
        for (String s : in) {
            out.add(c(s));
        }
        return out;
    }

    /** Рядок із кодами § (для PlaceholderAPI). */
    public static String legacy(String amp) {
        return SECTION.serialize(AMP.deserialize(amp == null ? "" : amp));
    }

    public static String plain(String amp) {
        return PlainTextComponentSerializer.plainText().serialize(AMP.deserialize(amp == null ? "" : amp));
    }

    /** 1234567 -> "1 234 567". */
    public static String num(long n) {
        return String.format("%,d", n).replace(',', ' ').replace(' ', ' ').replace(' ', ' ');
    }

    /** Смужка прогресу з 10 сегментів. */
    public static String bar(long current, long target, String filled, String empty) {
        int segments = 10;
        long cur = Math.max(0, Math.min(current, target));
        int f = target <= 0 ? segments : (int) Math.round((double) cur / (double) target * segments);
        StringBuilder sb = new StringBuilder();
        sb.append(filled);
        for (int i = 0; i < f; i++) {
            sb.append('█');
        }
        sb.append(empty);
        for (int i = f; i < segments; i++) {
            sb.append('█');
        }
        return sb.toString();
    }
}
