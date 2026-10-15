package com.easyplayerreport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector de JSON minimo, sin librerias.
 *
 * El plugin hasta ahora solo ESCRIBIA json a mano (DiscordClient) y nunca habia
 * tenido que leerlo. Con los tickets hace falta: al crear un canal Discord
 * devuelve su id, y al leer el historial hay que sacar el autor, el texto y los
 * adjuntos de cada mensaje.
 *
 * Se escribe a mano y no se usa Gson ni nada por dos razones: el JAR no crece,
 * y el codigo es estatico y se puede probar sin levantar un servidor de Bukkit.
 * Solo cubre lo que devuelve la API de Discord, que es JSON plano: sin claves
 * repetidas y sin numeros raros.
 *
 * Los valores salen como LinkedHashMap, ArrayList, String, Long, Double,
 * Boolean o null. Discord devuelve los ids como texto, no como numero, asi que
 * un id nunca pasa por aqui como Long.
 */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** Lee un JSON completo. Devuelve null si esta vacio o mal formado. */
    public static Object parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Json parser = new Json(raw);
            parser.skipSpaces();
            Object value = parser.readValue();
            return value;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Lee un JSON que deberia ser un objeto. Si no lo es, devuelve un mapa vacio. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String raw) {
        Object value = parse(raw);
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return new LinkedHashMap<>();
    }

    /** Lee un JSON que deberia ser una lista, como la que devuelve /messages. */
    public static List<Object> list(String raw) {
        Object value = parse(raw);
        if (value instanceof List<?> list) return new ArrayList<>(list);
        return new ArrayList<>();
    }

    // ------------------------------------------------------------ atajos

    /** Texto de una clave, o cadena vacia si no esta o no es texto. */
    public static String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String text ? text : "";
    }

    /** Subobjeto de una clave, o un mapa vacio. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> child(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Map<?, ?> child) return (Map<String, Object>) child;
        return new LinkedHashMap<>();
    }

    /** Lista de una clave, o una lista vacia. */
    public static List<Object> array(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof List<?> list) return new ArrayList<>(list);
        return new ArrayList<>();
    }

    // ------------------------------------------------------------ lectura

    private Object readValue() {
        if (pos >= text.length()) throw new IllegalArgumentException("fin del texto");
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++; // {
        skipSpaces();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (pos < text.length()) {
            skipSpaces();
            String key = readString();
            skipSpaces();
            expect(':');
            skipSpaces();
            result.put(key, readValue());
            skipSpaces();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            }
            if (next == '}') {
                pos++;
                return result;
            }
            throw new IllegalArgumentException("se esperaba , o } en la posicion " + pos);
        }
        throw new IllegalArgumentException("objeto sin cerrar");
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        pos++; // [
        skipSpaces();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (pos < text.length()) {
            skipSpaces();
            result.add(readValue());
            skipSpaces();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            }
            if (next == ']') {
                pos++;
                return result;
            }
            throw new IllegalArgumentException("se esperaba , o ] en la posicion " + pos);
        }
        throw new IllegalArgumentException("lista sin cerrar");
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) break;
            char escaped = text.charAt(pos++);
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(readUnicode());
                default -> out.append(escaped);
            }
        }
        throw new IllegalArgumentException("texto sin cerrar");
    }

    /** Escapado unicode de 4 digitos hexadecimales. Discord lo usa en los emojis. */
    private char readUnicode() {
        if (pos + 4 > text.length()) throw new IllegalArgumentException("unicode incompleto");
        String hex = text.substring(pos, pos + 4);
        pos += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("unicode invalido: " + hex);
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-' || peek() == '+') pos++;
        boolean decimal = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                decimal |= (c == '.' || c == 'e' || c == 'E');
                pos++;
            } else {
                break;
            }
        }
        String raw = text.substring(start, pos);
        if (raw.isEmpty()) throw new IllegalArgumentException("numero vacio");
        try {
            return decimal ? (Object) Double.valueOf(raw) : (Object) Long.valueOf(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("numero invalido: " + raw);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) throw new IllegalArgumentException("se esperaba " + word);
        pos += word.length();
        return value;
    }

    private void skipSpaces() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private char peek() {
        if (pos >= text.length()) throw new IllegalArgumentException("fin del texto");
        return text.charAt(pos);
    }

    private void expect(char expected) {
        if (pos >= text.length() || text.charAt(pos) != expected) {
            throw new IllegalArgumentException("se esperaba " + expected + " en la posicion " + pos);
        }
        pos++;
    }
}
