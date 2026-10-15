package com.easyplayerreport;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Carga menus.yml con los textos (y colores) de los menus de dialogo.
 * Cada clave es un solo texto. Se soporta el formato legacy con codigos &
 * (ej: "&cCancelar") y el formato MiniMessage (ej: "<b><gradient:#FF003E:#FF0086>Aviso</gradient></b>").
 *
 * Etiqueta <center>: en las lineas donde se sepa la anchura de la ventana
 * (como el aviso del boton "!"), marca la linea para que se centre sola con
 * espacios calculados automaticamente. En el resto de textos se ignora y se
 * elimina para que no se vea.
 */
public class Menus {
    private final EasyPlayerReport plugin;
    private YamlConfiguration cfg;

    public Menus(EasyPlayerReport plugin) {
        this.plugin = plugin;
        if (!new File(plugin.getDataFolder(), "menus.yml").exists()) plugin.saveResource("menus.yml", false);
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "menus.yml");
        cfg = YamlConfiguration.loadConfiguration(file);
        // Igual que en Lang: si el jugador ya tenia menus.yml de una version
        // anterior, las claves nuevas no estan y los textos se quedarian en
        // blanco. Se rellenan solas desde el JAR.
        List<String> added = YamlMerge.mergeDefaults(plugin, "menus.yml", file);
        if (!added.isEmpty()) {
            plugin.getLogger().info(added.size() == 1
                    ? "Se añadió el texto nuevo de menus.yml: " + added.get(0)
                    : "Se añadieron " + added.size() + " textos nuevos a menus.yml: " + String.join(", ", added));
        }
    }

    public String raw(String path, String def) {
        String v = cfg.getString(path);
        return normalizeUnicode((v == null || v.trim().isEmpty()) ? def : v);
    }

    public Component comp(String path, String defText, TextColor defColor) {
        return comp(path, defText, defColor, (String[]) null);
    }

    public Component comp(String path, String defText, TextColor defColor, String... replace) {
        String v = cfg.getString(path);
        if (v == null || v.trim().isEmpty()) {
            v = defText;
        }
        if (replace != null) {
            for (int i = 0; i + 1 < replace.length; i += 2) {
                v = v.replace(replace[i], replace[i + 1]);
            }
        }
        return build(stripMarkup(normalizeUnicode(v)), defColor);
    }

    public static Component build(String v, TextColor defColor) {
        v = normalizeUnicode(v);
        if (v == null || v.isEmpty()) return Component.empty().color(defColor);
        if (v.contains("<")) {
            try {
                return MiniMessage.miniMessage().deserialize(v);
            } catch (Exception ignored) {
                // no es MiniMessage valido: cae al codigo legacy o plano
            }
        }
        if (v.contains("&")) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(v);
        }
        return Component.text(v).color(defColor);
    }

    public static String normalizeUnicode(String value) {
        return value == null ? null : value.replace("\uFE0F", "");
    }

    /** Quita las etiquetas <center> de un texto. */
    public static String stripMarkup(String s) {
        return s == null ? "" : s.replace("<center>", "").replace("</center>", "");
    }

    /** Quita los codigos de color & y las etiquetas MiniMessage (<...>). */
    public static String stripCodes(String s) {
        if (s == null) return "";
        String out = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s));
        return out.replaceAll("<[^>]*>", "");
    }

    /**
     * Ancho aproximado en pixeles de un texto con la fuente por defecto del
     * juego. Sirve para centrar lineas de un tooltip sin usar espacios a mano.
     */
    public static int textWidth(String s) {
        int px = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 126 || c == '\u00A1' || c == '\u00BF' || c == '\u2757') {
                px += 6;
                continue;
            }
            switch (c) {
                case ' ':  px += 4; break;
                case 'i': case 'l': case 't': case 'j': case 'r':
                case 'I': case 'L': case 'J': case 'T':
                case '|': case '.': case ',': case ':': case ';':
                case '\'': case '!': px += 4; break;
                case 'm': case 'w': case 'M': case 'W': case '@': px += 8; break;
                default:   px += 6; break;
            }
        }
        return px;
    }

    public static Component buildCentered(String v, TextColor defColor, int targetPx) {
        int gap = Math.max(0, targetPx - textWidth(stripCodes(v)));
        int spaces = gap / 4;
        return Component.text(" ".repeat(spaces)).append(build(stripMarkup(v), defColor));
    }

    public static List<String> wrapText(String text, int maxPx) {
        String plain = text == null ? "" : text;
        List<String> lines = new ArrayList<>();
        for (String paragraph : plain.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            // Se miden las palabras ya sin codigos de color: contar &c como dos
            // caracteres de ancho haria que se cortara antes de lo necesario.
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && textWidth(stripCodes(candidate)) > maxPx) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    public static Component buildLines(List<String> lines, TextColor defColor) {
        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) result = result.append(Component.newline());
            result = result.append(build(lines.get(i), defColor));
        }
        return result;
    }
}