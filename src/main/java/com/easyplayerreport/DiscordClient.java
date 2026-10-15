package com.easyplayerreport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Envia mensajes a un canal de Discord por la API REST, sin librerias.
 *
 * Se hace a proposito con HttpClient (que ya trae Java) en lugar de JDA: JADA
 * arrastra librerias nativas y el JAR pasaria de 140 KB a varios MB. Aqui solo
 * hace falta un POST a /channels/{id}/messages, que es trivial.
 *
 * Los envios son siempre asincronos. El resto del plugin trabaja en el hilo
 * principal de Bukkit, y una llamada de red bloqueando ese hilo congelaria el
 * servidor entero si Discord tarda.
 */
public class DiscordClient {
    private static final String API = "https://discord.com/api/v10";
    /** Discord corta el contenido a 2000 caracteres. */
    private static final int MAX_CONTENT = 2000;
    /** Y la descripcion de un embed a 4096. */
    private static final int MAX_EMBED_DESCRIPTION = 4096;
    /** Cada valor de campo admite 1024. */
    private static final int MAX_FIELD_VALUE = 1024;
    /** El nombre de un campo, 256. */
    private static final int MAX_FIELD_NAME = 256;
    /** Permisos en binario, para el canal de un ticket. */
    private static final long VIEW_CHANNEL = 1024L;
    private static final long SEND_MESSAGES = 2048L;
    private static final long READ_HISTORY = 65536L;
    private static final long EMBED_LINKS = 16384L;

    private final EasyPlayerReport plugin;
    private final DiscordConfig config;
    private final HttpClient http;

    public DiscordClient(EasyPlayerReport plugin, DiscordConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------ embeds

    /** Un campo del embed: nombre a la izquierda, valor a la derecha. */
    public record Field(String name, String value, boolean inline) {
        /** Discord exige name y value no vacios; si faltan, no se manda el campo. */
        public boolean valid() {
            return name != null && !name.isBlank() && value != null && !value.isBlank();
        }
    }

    /**
     * Envia un embed al canal de avisos.
     *
     * Antes este metodo acepataba botones, pero se eliminaron: el plugin puede
     * enviarlos, nunca recibir el clic, y con los tickets el canal lo crea el
     * plugin al tomar el reporte, asi que no habia nada que pulsar.
     */
    public CompletableFuture<Void> sendEmbed(String title, String description, String colorHex,
                                             List<Field> fields, String footer, String content) {
        if (!config.isReady()) return CompletableFuture.completedFuture(null);

        String body = buildPayload(title, description, colorHex, fields, footer, content);
        if (body == null) return CompletableFuture.completedFuture(null);
        return post(body);
    }

    /**
     * Monta el JSON del POST. Devuelve null si no queda nada que enviar,
     * para no gastar una llamada a Discord en balde.
     *
     * Es static a proposito: no usa nada del objeto, solo sus parametros. Asi se
     * puede probar el JSON generado sin levantar un servidor de Bukkit.
     */
    static String buildPayload(String title, String description, String colorHex,
                               List<Field> fields, String footer, String content) {
        StringBuilder json = new StringBuilder("{\"embeds\":[{");
        json.append("\"title\":").append(quote(cut(title, 256))).append(',');
        json.append("\"color\":").append(colorHex).append(',');
        if (description != null && !description.isBlank()) {
            json.append("\"description\":").append(quote(cut(description, MAX_EMBED_DESCRIPTION))).append(',');
        }
        if (fields != null && !fields.isEmpty()) {
            json.append("\"fields\":[");
            boolean first = true;
            for (Field field : fields) {
                if (field == null || !field.valid()) continue;
                if (!first) json.append(',');
                first = false;
                json.append("{\"name\":").append(quote(cut(field.name(), MAX_FIELD_NAME)))
                        .append(",\"value\":").append(quote(cut(field.value(), MAX_FIELD_VALUE)))
                        .append(",\"inline\":").append(field.inline()).append('}');
            }
            // Cierre del array de campos. Sin esto el JSON queda mal formado y
            // Discord responde 400 en TODOS los avisos.
            json.append(']');
        }
        if (footer != null && !footer.isBlank()) {
            json.append(",\"footer\":{\"text\":").append(quote(cut(footer, 2048))).append('}');
        }
        // timestamp va en epoch, Discord lo pasa a fecha legible
        json.append(",\"timestamp\":\"").append(java.time.Instant.now().toString()).append('"');
        json.append("}]");

        if (content != null && !content.isBlank()) {
            json.append(",\"content\":").append(quote(cut(content, MAX_CONTENT)));
        }
        // Sin esto, un @everyone escrito dentro de la descripcion de un reporte
        // mencionaria a todo el servidor. Con "parse" vacio no se menciona a nadie.
        json.append(",\"allowed_mentions\":{\"parse\":[]}");
        json.append('}');
        return json.toString();
    }

    // ------------------------------------------------------------ envio

    private CompletableFuture<Void> post(String body) {
        return send("/channels/" + config.channelId() + "/messages", "POST", body, 1,
                Math.max(0, config.retryAttempts()) + 1).thenApply(ignored -> null);
    }

    /**
     * Una peticion cualquiera a la API. El cuerpo de la respuesta se devuelve
     * como texto para poder leerlo con Json; si solo interesa que responda bien,
     * se descarta.
     *
     * Todas las llamadas son asincronas y nunca lanzan hacia el hilo principal: el
     * resto del plugin trabaja en el hilo de Bukkit y una llamada de red
     * bloqueando ahi congelaria el servidor entero.
     */
    public CompletableFuture<String> send(String path, String method, String body, int current, int total) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(API + path))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bot " + config.token())
                .header("User-Agent", "EasyPlayerReport/" + plugin.getPluginMeta().getVersion() + " (Paper)");

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpRequest request = builder.build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int code = response.statusCode();
                    if (code >= 200 && code < 300) return CompletableFuture.completedFuture(response.body());
                    // 429: Discord dice cuanto esperar en el header retry_after.
                    if (code == 429 && current < total) {
                        if (config.logErrors()) {
                            plugin.getLogger().warning("Discord limito la peticion (429) en " + path + ", reintentando.");
                        }
                        return delay(2).thenCompose(ignored -> send(path, method, body, current + 1, total));
                    }
                    if (config.logErrors()) {
                        // El cuerpo puede traer el campo "message" con el motivo real.
                        plugin.getLogger().warning("No se pudo avisar a Discord (HTTP " + code + ") en " + path
                                + ": " + extractDiscordMessage(response.body()));
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .exceptionally(throwable -> {
                    if (config.logErrors()) {
                        plugin.getLogger().warning("Fallo de red al avisar a Discord: "
                                + throwable.getClass().getSimpleName());
                    }
                    return null;
                });
    }

    /** Espera sin bloquear ningun hilo. */
    private CompletableFuture<Void> delay(long seconds) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Saca el campo "message" de la respuesta de Discord, que explica el
     * motivo real. Si el cuerpo no es el esperado, se devuelve recortado.
     */
    private String extractDiscordMessage(String body) {
        if (body == null || body.isBlank()) return "sin respuesta";
        int key = body.indexOf("\"message\"");
        if (key < 0) return cut(body, 180);
        int colon = body.indexOf(':', key);
        if (colon < 0) return cut(body, 180);
        int quoteStart = body.indexOf('"', colon);
        if (quoteStart < 0) return cut(body, 180);
        int quoteEnd = body.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return cut(body, 180);
        return body.substring(quoteStart + 1, quoteEnd);
    }

    // ------------------------------------------------------------ canales

    /**
     * Un mensaje leido del historial de un canal. Es record y no una clase con
     * getters porque no cambia nunca y asi se crea en una linea al leer el JSON.
     */
    public record DiscordMessage(String id, String authorId, String authorName, String content,
                                 List<String> attachments, boolean bot) {
    }

    /**
     * Crea un canal de texto para un reporte.
     *
     * La API de Discord no tiene "crear hilo": un ticket es un canal mas, asi que
     * se crea con POST /guilds/{guild}/channels. Por eso hace falta el ID del
     * servidor en la config, que es lo unico que no se puede deducir.
     *
     * Devuelve el id del canal nuevo, o null si Discord lo rechaza. Si se indica
     * un rol, ese rol recibe permiso de escritura y el resto lo que diga la
     * categoria: por defecto el canal queda en privado para el staff. Sin rol, el
     * canal hereda los permisos de la categoria tal cual.
     */
    public CompletableFuture<String> createTextChannel(String guildId, String name, String topic,
                                                       String categoryId, String roleId) {
        if (config.token().isEmpty() || guildId == null || guildId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        StringBuilder json = new StringBuilder("{\"name\":").append(quote(name))
                .append(",\"type\":0");
        if (categoryId != null && !categoryId.isEmpty()) {
            json.append(",\"parent_id\":").append(quote(categoryId));
        }
        if (topic != null && !topic.isBlank()) {
            json.append(",\"topic\":").append(quote(cut(topic, 1024)));
        }
        if (roleId != null && !roleId.isEmpty()) {
            // type 0 = rol. allow en binario: ver el canal (1024), enviar (2048),
            // historial (65536) y enlaces (16384).
            json.append(",\"permission_overwrites\":[{\"id\":").append(quote(roleId))
                    .append(",\"type\":0,\"allow\":\"").append(VIEW_CHANNEL | SEND_MESSAGES | READ_HISTORY | EMBED_LINKS)
                    .append("\",\"deny\":\"0\"}]");
        }
        json.append('}');
        return send("/guilds/" + guildId + "/channels", "POST", json.toString(), 1,
                Math.max(0, config.retryAttempts()) + 1)
                .thenApply(response -> response == null ? null : Json.string(Json.object(response), "id"));
    }

    /**
     * Envia un mensaje de un chat de reporte al canal.
     *
     * Discord deja cambiar el nombre que aparece como autor de un mensaje
     * (campo username), asi que el mensaje sale con el nombre del jugador y no
     * con el del bot. El nombre tambien va dentro del texto: si ese campo no se
     * respeta, el mensaje sigue entendiendose.
     */
    public CompletableFuture<Void> sendChatMessage(String channelId, String username, String content) {
        String body = "{\"content\":" + quote(cut(content, MAX_CONTENT))
                + ",\"username\":" + quote(cut(username == null || username.isBlank() ? "Juego" : username, 80))
                // Sin esto, un @everyone escrito por un jugador en el chat
                // mencionaria a todo el servidor de Discord.
                + ",\"allowed_mentions\":{\"parse\":[]}}";
        return send("/channels/" + channelId + "/messages", "POST", body, 1,
                Math.max(0, config.retryAttempts()) + 1).thenApply(ignored -> null);
    }

    /** Envia un embed a un canal concreto, que no es el de los avisos. */
    public CompletableFuture<Void> sendEmbed(String channelId, String title, String description, String colorHex,
                                             List<Field> fields, String footer, String content) {
        if (channelId == null || channelId.isEmpty()) return CompletableFuture.completedFuture(null);
        String body = buildPayload(title, description, colorHex, fields, footer, content);
        if (body == null) return CompletableFuture.completedFuture(null);
        return send("/channels/" + channelId + "/messages", "POST", body, 1,
                Math.max(0, config.retryAttempts()) + 1).thenApply(ignored -> null);
    }

    /**
     * Archiva o desarchiva un canal. Archivar no borra nada: el chat se puede
     * reabrir con PATCH y sigue ahi, que es justo lo que se quiere al cerrar un
     * reporte para que quede el historial como prueba.
     */
    public CompletableFuture<Void> setArchived(String channelId, boolean archived) {
        if (channelId == null || channelId.isEmpty()) return CompletableFuture.completedFuture(null);
        return send("/channels/" + channelId, "PATCH", "{\"archived\":" + archived + "}", 1, 2)
                .thenApply(ignored -> null);
    }

    public CompletableFuture<Void> deleteChannel(String channelId) {
        if (channelId == null || channelId.isEmpty()) return CompletableFuture.completedFuture(null);
        return send("/channels/" + channelId, "DELETE", null, 1, 2).thenApply(ignored -> null);
    }

    /**
     * Lee los mensajes posteriores a un id del canal.
     *
     * Asi es como entra Discord en el juego: GET /channels/{id}/messages?after=..
     * devuelve lo escrito despues de ese mensaje, y como son ids que van
     * contando hacia atras, el after se guarda como "el ultimo que ya hemos
     * leido". Discord devuelve los mas recientes primero, asi que se da la vuelta
     * para devolverlos en orden de escritura.
     */
    public CompletableFuture<List<DiscordMessage>> fetchMessages(String channelId, String afterId, int limit) {
        if (channelId == null || channelId.isEmpty()) return CompletableFuture.completedFuture(List.of());
        String path = "/channels/" + channelId + "/messages?limit=" + Math.max(1, Math.min(100, limit));
        if (afterId != null && afterId.matches("\\d{15,25}")) path += "&after=" + afterId;
        return send(path, "GET", null, 1, Math.max(0, config.retryAttempts()) + 1)
                .thenApply(response -> parseMessages(response));
    }

    /**
     * Id del propio bot. Hace falta para no devolver al juego los mensajes que el
     * propio plugin acaba de mandar a Discord: si no, cada mensaje del juego
     * volveria como mensaje de Discord y se repetirian solos.
     */
    public CompletableFuture<String> selfUserId() {
        return send("/users/@me", "GET", null, 1, 2)
                .thenApply(response -> response == null ? null : Json.string(Json.object(response), "id"));
    }

    /**
     * Primer servidor en el que esta el bot. Se usa solo si falta guild-id en la
     * config, para que un servidor con un unico bot no tenga que buscar el id.
     */
    public CompletableFuture<String> anyGuildId() {
        return send("/users/@me/guilds", "GET", null, 1, 2).thenApply(response -> {
            for (Object entry : Json.list(response)) {
                if (entry instanceof Map<?, ?> map) {
                    Object id = map.get("id");
                    if (id instanceof String text && !text.isBlank()) return text;
                }
            }
            return null;
        });
    }

    /** Convierte la respuesta de /messages en mensajes legibles, ya en orden. */
    private List<DiscordMessage> parseMessages(String response) {
        List<DiscordMessage> result = new ArrayList<>();
        if (response == null) return result;
        for (Object entry : Json.list(response)) {
            if (!(entry instanceof Map<?, ?> raw)) continue;
            Map<String, Object> message = asObject(raw);
            Map<String, Object> author = Json.child(message, "author");
            List<String> attachments = new ArrayList<>();
            for (Object attachment : Json.array(message, "attachments")) {
                if (attachment instanceof Map<?, ?> file) {
                    Object url = file.get("url");
                    if (url instanceof String text && !text.isBlank()) attachments.add(text);
                }
            }
            result.add(new DiscordMessage(
                    Json.string(message, "id"),
                    Json.string(author, "id"),
                    Json.string(author, "username"),
                    Json.string(message, "content"),
                    attachments,
                    Boolean.TRUE.equals(author.get("bot"))));
        }
        // Discord devuelve el historial del mas nuevo al mas viejo.
        java.util.Collections.reverse(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObject(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    // ------------------------------------------------------------ utiles

    /** Mensaje de prueba, para comprobar token y canal al recargar. */
    public CompletableFuture<Void> sendTest() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Estado", "La configuracion de discord.yml es correcta.", true));
        return sendEmbed("Avisos de Discord activados", "Este mensaje confirma que el token y el canal funcionan.",
                config.color(), fields, "EasyPlayerReport", null);
    }

    /**
     * Menciona al rol configurado o a @everyone si esta activado.
     * Devuelve cadena vacia si no hay que mencionar a nadie.
     */
    public String buildMention() {
        if (config.pingHere()) return "@everyone";
        String role = config.pingRoleId();
        if (role.isEmpty()) return "";
        // El ID de un snowflake son 15-25 digitos. Si lleva texto no se menciona.
        return role.matches("\\d{15,25}") ? "<@&" + role + ">" : "";
    }

    /**
     * Escapa una cadena para meterla en JSON. Sin esto, una comilla en la
     * descripcion de un reporte rompe el JSON entero y no llega nada.
     */
    static String quote(String raw) {
        String value = raw == null ? "" : raw;
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Caracteres de control, que JSON no admite sin escapar.
                    if (c < 0x20 || c == 0x7F) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /** Recorta a un maximo sin partir un caracter de mas a la mitad. */
    private static String cut(String raw, int max) {
        if (raw == null) return "";
        if (raw.length() <= max) return raw;
        return raw.substring(0, Math.max(0, max - 1)) + "â€¦";
    }

    /**
     * Quita los codigos de color de Minecraft de un texto.
     *
     * El plugin los usa para el chat (&a, &6, &l), pero en Discord no existen
     * y se veian tal cual dentro del embed. Se limpian tanto las etiquetas
     * propias (el estado) como lo que escriben los jugadores.
     *
     * Solo se quita un codigo si la "&" esta al principio del texto o tras un
     * espacio. Si se quitara siempre, "R&D de la empresa" se convertiria en
     * "R de la empresa", porque &D parece un color valido, y eso seria perder
     * texto del jugador en lugar de solo dejar unç¬¦å· suelto.
     *
     * Al final se recoge una "&" que quede al principio, que es el caso de
     * "&&Abierto".
     */
    static String plain(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length());
        // true al principio, y sigue siendo true mientras encadenamos codigos
        // ("&a&l&n"), para poder quitar la cadena entera de golpe.
        boolean atBoundary = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean isAmp = c == '&' || c == '\u00A7';
            if (isAmp && atBoundary && i + 1 < raw.length() && isColorCode(raw.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
            atBoundary = Character.isWhitespace(c);
        }
        String result = out.toString();
        while (result.startsWith("&") || result.startsWith("\u00A7")) {
            result = result.substring(1);
        }
        return result.trim();
    }

    private static boolean isColorCode(char c) {
        return "0123456789abcdefklmnorxABCDEFKLMNORX".indexOf(c) >= 0;
    }
}
