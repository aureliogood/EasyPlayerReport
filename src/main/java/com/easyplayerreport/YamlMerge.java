package com.easyplayerreport;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rellena en los archivos de configuracion del servidor las claves nuevas que
 * aun no tenga.
 *
 * POR QUE ESTO HACE FALTA
 *
 * Bukkit solo copia un recurso del JAR si el archivo no existe en la carpeta
 * del servidor. Traducido: cuando el plugin trae claves nuevas (un mensaje
 * nuevo, una opcion nueva) y el jugador ya tiene el archivo de antes, esas
 * claves nunca llegan a su disco.
 *
 * El sintoma es silencioso y confuso: el codigo pide el texto, no lo
 * encuentra, y cae al valor por defecto, que en muchos casos es una cadena
 * vacia. El jugador ve un renglon en blanco en el chat y no hay ningun error
 * en consola.
 *
 * Aqui se comparan las claves del JAR con las del archivo del servidor y se
 * anaden solo las que falten, escribiendo encima lo que el usuario ya tenga.
 * El archivo se reescribe una unica vez: al anadir las claves de esta version
 * ya no se vuelve a tocar en los siguientes arranques.
 */
final class YamlMerge {

    private YamlMerge() {
    }

    /**
     * Devuelve las claves que se anadieron, para poder avisar por consola.
     * Si el recurso del JAR no existe, o el archivo del servidor tampoco, no
     * hace nada y devuelve una lista vacia.
     */
    static List<String> mergeDefaults(EasyPlayerReport plugin, String jarPath, File file) {
        List<String> added = new ArrayList<>();
        if (plugin == null || jarPath == null || file == null || !file.isFile()) return added;

        YamlConfiguration defaults = readFromJar(plugin, jarPath);
        if (defaults == null) return added;

        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        Set<String> missing = collectMissing(current, defaults);
        if (missing.isEmpty()) return added;

        for (String key : missing) current.set(key, defaults.get(key));
        try {
            current.save(file);
        } catch (Exception exception) {
            plugin.getLogger().warning("No se pudieron anadir las claves nuevas a " + file.getName()
                    + ": " + exception.getMessage());
            return new ArrayList<>();
        }
        added.addAll(missing);
        return added;
    }

/**
     * Claves del recurso del JAR que no estan en el archivo del servidor.
     *
     * Solo se miran las hojas (valores y listas), nunca las secciones: al
     * escribir una clave como "messages.discord-off", Bukkit ya crea por su
     * cuenta la seccion "messages" que haga falta. Asi una seccion entera que
     * le falte al usuario se reconstruye sola.
     */
private static Set<String> collectMissing(YamlConfiguration current, YamlConfiguration defaults) {
        Set<String> missing = new TreeSet<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!current.contains(key)) missing.add(key);
        }
        return missing;
    }

    private static YamlConfiguration readFromJar(EasyPlayerReport plugin, String jarPath) {
        try (InputStream stream = plugin.getResource(jarPath)) {
            if (stream == null) return null;
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception exception) {
            return null;
        }
    }
}