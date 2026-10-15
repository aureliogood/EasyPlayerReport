package com.easyplayerreport;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Canal de Discord por reporte, con el chat sincronizado en los dos sentidos.
 *
 * Cuando un staff toma un reporte en el juego, se crea un canal en la categoria
 * configurada. A partir de ahi:
 *
 *   - lo que se escribe en el chat del dialogo se manda al canal;
 *   - lo que se escribe en el canal aparece en el chat del dialogo.
 *
 * COMO SE HACE SIN SERVICIO EXTERNO
 *
 * Lo que hacia falta antes era un boton con un custom_id, porque el plugin no
 * puede enterarse de que le pulsan: eso necesita la conexion Gateway. Al crear
 * el canal desde el propio plugin (cuando el staff toma el reporte) ese boton
 * sobra, y la vuelta se resuelve con la API REST: un bot puede leer el
 * historial de un canal con GET /channels/{id}/messages usando solo su token.
 * Se pregunta cada ticket.poll-seconds por lo nuevo que hay despues del ultimo
 * mensaje leido y se pasa al chat del juego.
 *
 * El precio es un retraso de unos segundos y que no se ven los "esta escribiendo",
 * a cambio de no montar ningun servicio, no abrir ningun puerto en el servidor
 * de Minecraft y no anadir ni una libreria al JAR.
 *
 * El id del canal se guarda en el reporte, asi que sobrevive a un reinicio: al
 * arrancar se vuelve a leer el canal desde el ultimo mensaje, sin repetir en el
 * juego los mensajes antigos.
 *
 * IMPORTANTE CON EL HILO
 *
 * Todo el trabajo de red va asincrono, pero tocar el chat y guardarlo se hace
 * desde el hilo principal de Bukkit con runTask. Si se escribiera en el
 * chatLog desde el hilo de red, el staff veria la lista a medio construir.
 */
public class DiscordTickets {
    private final EasyPlayerReport plugin;
    // Concurrent porque lo tocan el hilo de la tarea de lectura y el de las
    // respuestas de Discord, que no son el mismo.
    private final Map<Integer, String> lastRead = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private DiscordConfig config;
    private DiscordClient client;
    private String selfId = "";
    private String guildId = "";
    private int taskId = -1;

    public DiscordTickets(EasyPlayerReport plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ arranque

    /** Si los tickets pueden funcionar. */
    public boolean isReady() {
        return config != null && config.ticketReady() && !guildId.isEmpty();
    }

    /**
     * Arranca la sincronizacion. Se llama al habilitar el plugin y despues de un
     * reload, asi que primero para la anterior: si no, se quedarian dos tareas
     * leyendo los mismos canales y cada mensaje entraria dos veces en el chat.
     */
    public void start() {
        stop();
        config = plugin.getDiscordConfig();
        client = new DiscordClient(plugin, config);
        if (!config.ticketEnabled()) {
            // Es facil pegar "enabled: true" sin el "ticket:" delante y entonces
            // esto se queda apagado sin decir nada, que es justo lo que paso con
            // el boton que sustituye.
            if (config.enabled() && !config.hasTicketSection()) {
                plugin.getLogger().warning("discord/discord.yml no tiene el bloque 'ticket:', asi que los canales "
                        + "de Discord por reporte no se pueden activar. Anadelo con 'ticket:' delante de enabled.");
            }
            return;
        }

        String problem = config.ticketProblem();
        if (!problem.isEmpty()) {
            plugin.getLogger().warning("Los tickets de Discord no se activan: " + problem);
            return;
        }

        guildId = config.guildId();
        if (guildId.isEmpty()) {
            // Sin guild-id se busca solo. Se avisa del id usado para que quede
            // claro en la consola, porque si el bot esta en varios servidores
            // se cogeria el primero y puede no ser el que se quiere.
            plugin.getLogger().info("No hay guild-id en discord.yml. Buscando el servidor donde esta el bot...");
            client.anyGuildId().thenAccept(found -> {
                if (found == null || found.isBlank()) {
                    plugin.getLogger().warning("El bot no esta en ningun servidor de Discord, o el token no es valido. "
                            + "Los tickets se quedan desactivados.");
                    return;
                }
                guildId = found;
                plugin.getLogger().info("Usando el servidor " + found + " para los tickets de Discord. "
                        + "Si no es el que quieres, pon guild-id en discord/discord.yml.");
                begin();
            });
            return;
        }
        begin();
    }

    private void begin() {
        client.selfUserId().thenAccept(id -> {
            if (id != null) selfId = id;
            int ticks = Math.max(20, config.ticketPollSeconds() * 20);
            taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollAll, ticks, ticks).getTaskId();
            plugin.getLogger().info("Tickets de Discord activados. Se creara un canal al tomar cada reporte, "
                    + "revisando el chat de Discord cada " + config.ticketPollSeconds() + " s.");
        });
    }

    /** Para la tarea de lectura. Se llama al recargar la config y al apagar. */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        lastRead.clear();
        inFlight.clear();
    }

    // ------------------------------------------------------------ eventos

    /**
     * Un staff ha tomado el reporte: se crea el canal y se manda dentro el
     * resumen del reporte, para que quien entre al canal sin contexto sepa de
     * que va.
     */
    public void onTaken(Report report, String staffName) {
        if (!isReady() || report == null || report.getId() <= 0) return;
        if (!report.getDiscordChannelId().isBlank()) return;

        String name = channelName(report);
        String topic = "Reporte #" + report.getId()
                + " · Reportado a: " + plain(report.getTarget())
                + " · Motivo: " + plain(report.getReason());
        client.createTextChannel(guildId, name, topic, config.ticketCategoryId(), config.ticketStaffRoleId())
                .thenAccept(channelId -> {
                    if (channelId == null || channelId.isBlank()) {
                        // Discord no lo ha creado. El reporte sigue siendo valido
                        // en el juego, solo se pierde el canal: se avisa por consola
                        // y se sigue.
                        plugin.getLogger().warning("No se pudo crear el canal de Discord para el reporte #"
                                + report.getId() + ". Revisa que el bot pueda crear canales en la categoria "
                                + "de ticket.category-id (permiso Gestionar canales).");
                        return;
                    }
                    // El id se guarda y el embed se manda desde el hilo
                    // principal: la lista de reportes y el guardado son del hilo
                    // de Bukkit, y tocarlo desde el hilo de red los deja a medias.
                    Bukkit.getScheduler().runTask(plugin, () -> attachChannel(report, channelId, staffName));
                });
    }

    private void attachChannel(Report report, String channelId, String staffName) {
        Report current = plugin.getReportManager().getById(report.getId());
        if (current == null) return;
        current.setDiscordChannelId(channelId);
        plugin.getReportManager().save();

        List<DiscordClient.Field> fields = new ArrayList<>();
        fields.add(new DiscordClient.Field("Reportado por", plain(current.getReporterName()), true));
        fields.add(new DiscordClient.Field("Reportado a", plain(current.getTarget()), true));
        fields.add(new DiscordClient.Field("Motivo", plain(current.getReason()), true));
        fields.add(new DiscordClient.Field("Estado", plugin.getLang().plainStatusLabel(current.getStatus()), true));
        fields.add(new DiscordClient.Field("Tomado por", plain(staffName), true));
        if (!plain(current.getDescription()).isBlank()) {
            fields.add(new DiscordClient.Field("Lo que escribio el jugador",
                    plain(current.getDescription()), false));
        }
        client.sendEmbed(channelId, "Reporte #" + current.getId() + " en curso",
                "El chat de este canal va al chat del reporte en el juego, en los dos sentidos.",
                config.color(), fields, null, null);

        // Se apunta ya el ultimo mensaje del canal para que el primer sondeo no
        // devuelva el embed de arriba ni nada escrito antes de que se crease.
        prime(current, channelId);
    }

    /** Un mensaje escrito en el chat del reporte dentro del juego. */
    public void onChatMessage(Report report, String senderName, String message) {
        if (!isReady() || report == null || !config.ticketSyncToDiscord()) return;
        String channelId = report.getDiscordChannelId();
        if (channelId.isBlank()) return;
        String author = plain(senderName);
        String text = plain(message);
        if (text.isBlank()) return;
        client.sendChatMessage(channelId, author.isBlank() ? "Juego" : author, "**" + author + "**: " + text);
    }

    /**
     * Se cierra el reporte: se avisa dentro del canal y se archiva, para que el
     * chat se quede ahi como registro pero sin poder escribir.
     */
    public void onClosed(Report report) {
        if (!isReady() || report == null) return;
        String channelId = report.getDiscordChannelId();
        if (channelId.isBlank()) return;
        client.sendEmbed(channelId, "Reporte #" + report.getId() + " cerrado",
                "El staff ha cerrado el reporte en el juego. Este canal queda archivado con el chat completo.",
                "#22c55e", List.of(), null, null);
        switch (config.ticketCloseAction()) {
            case "delete" -> client.deleteChannel(channelId);
            case "none" -> { }
            default -> client.setArchived(channelId, true);
        }
    }

    /** Se reabre: si el canal se archivó al cerrar, se abre para poder seguir. */
    public void onReopened(Report report) {
        if (!isReady() || report == null || !config.ticketUnarchiveOnReopen()) return;
        String channelId = report.getDiscordChannelId();
        if (channelId.isBlank()) return;
        client.setArchived(channelId, false);
        // Al reabrir se vuelve a apuntar al final del canal, en vez de seguir
        // desde donde se dejo: asi no entra de golpe en el chat lo que se
        // escribio en Discord mientras el canal estuvo archivado y cerrado.
        lastRead.remove(report.getId());
    }

    // ------------------------------------------------------------ lectura

    private void pollAll() {
        // Si un sondeo tarda mas que el intervalo, el siguiente se salta en vez
        // de solaparse: dos sondeos a la vez meterian los mismos mensajes dos
        // veces en el chat.
        if (!isReady() || !config.ticketSyncToGame()) return;
        if (!polling.compareAndSet(false, true)) return;
        try {
            for (Report report : plugin.getReportManager().getActive()) {
                String channelId = report.getDiscordChannelId();
                if (channelId.isBlank()) continue;
                fetch(report, channelId);
            }
        } finally {
            polling.set(false);
        }
    }

    private void fetch(Report report, String channelId) {
        int id = report.getId();
        String known = lastRead.get(id);
        if (known == null) {
            prime(report, channelId);
            return;
        }
        // Un canal por sondeo. El guardia global no basta: la peticion sale del
        // hilo de la tarea y la respuesta vuelve despues, asi que el siguiente
        // sondeo pudo salir ya con el mismo "ultimo mensaje leido" y los mismos
        // mensajes entrarian dos veces en el chat.
        if (!inFlight.add(id)) return;
        client.fetchMessages(channelId, known.isEmpty() ? null : known, config.ticketMaxMessagesPerPoll())
                .thenAccept(messages -> {
                    List<DiscordClient.DiscordMessage> fresh = new ArrayList<>();
                    for (DiscordClient.DiscordMessage message : messages) {
                        if (message.id() != null && !message.id().isBlank()) lastRead.put(id, message.id());
                        if (isOwnMessage(message) || isIgnoredBot(message)) continue;
                        if (message.content().isBlank() && message.attachments().isEmpty()) continue;
                        fresh.add(message);
                    }
                    if (fresh.isEmpty()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> deliver(report, fresh));
                })
                .whenComplete((result, error) -> inFlight.remove(id));
    }

    /**
     * Marca el canal como leido hasta ahora sin meter nada en el chat. Se usa al
     * crear el canal y al reconectar tras un reinicio, para no repetir en el
     * juego lo que ya se habia leido.
     */
    private void prime(Report report, String channelId) {
        int id = report.getId();
        if (!inFlight.add(id)) return;
        client.fetchMessages(channelId, null, 1).thenAccept(messages -> {
            if (messages.isEmpty()) {
                lastRead.put(id, "");
                return;
            }
            String newest = messages.get(messages.size() - 1).id();
            lastRead.put(id, newest == null ? "" : newest);
        }).whenComplete((result, error) -> inFlight.remove(id));
    }

    /** Los mensajes de Discord pasan al chat del reporte, en el hilo principal. */
    private void deliver(Report report, List<DiscordClient.DiscordMessage> messages) {
        Report current = plugin.getReportManager().getById(report.getId());
        if (current == null || !current.isActive()) return;
        for (DiscordClient.DiscordMessage message : messages) {
            String author = plain(message.authorName());
            if (author.isBlank()) author = "Discord";
            StringBuilder line = new StringBuilder("[Discord] ").append(author).append(": ");
            if (!message.content().isBlank()) line.append(plain(message.content()));
            if (config.ticketShowAttachments()) {
                for (String url : message.attachments()) {
                    if (line.length() > author.length() + 12) line.append(' ');
                    line.append("📎 ").append(url);
                }
            }
            plugin.getReportManager().addChatLine(current.getId(), line.toString());
        }
        plugin.getReportManager().flush(true);
        plugin.getReportChatDialog().onDiscordMessage(current, messages.size());
    }

    private boolean isOwnMessage(DiscordClient.DiscordMessage message) {
        // Sin id propio conocido no se puede filtrar, y es preferible mostrar de
        // mas que perder mensajes: el eco se ve raro, pero nada se pierde.
        return !selfId.isEmpty() && selfId.equals(message.authorId());
    }

    private boolean isIgnoredBot(DiscordClient.DiscordMessage message) {
        return message.bot() && config.ticketIgnoreBots();
    }

    // ------------------------------------------------------------ utiles

    /**
     * Nombre del canal a partir de la plantilla de la config. Discord solo
     * admite minusculas, guiones y numeros, asi que se limpia lo que no vale: un
     * motivo con acentos, emojis o simbolos daria un canal que Discord rechaza
     * con un error dificil de leer.
     */
    private String channelName(Report report) {
        String template = config.ticketChannelName();
        if (template == null || template.isBlank()) template = "reporte-{id}";
        String raw = template
                .replace("{id}", String.valueOf(report.getId()))
                .replace("{target}", plain(report.getTarget()))
                .replace("{reason}", plain(report.getReason()))
                .replace("{reporter}", plain(report.getReporterName()))
                .replace("{staff}", plain(report.getAssignedStaffName()));
        String name = sanitizeChannelName(raw);
        return name.isBlank() ? "reporte-" + report.getId() : name;
    }

    /**
     * Limpia un nombre para que Discord lo acepte, que solo admite minusculas,
     * guiones y numeros. Esta parte es pura y sin Bukkit, asi que se separa para
     * poder comprobarla con pruebas: un motivo con acentos, emojis o simbolos
     * daria un canal que Discord rechaza con un error dificil de entender.
     */
    static String sanitizeChannelName(String raw) {
        raw = raw.toLowerCase(Locale.ROOT);
        // Sin acentos antes de limpiar: si no, "Acción" se quedaria como
        // "acci-n", porque cada caracter no admitido se cambia por un guion.
        raw = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        StringBuilder clean = new StringBuilder();
        boolean lastDash = false;
        for (int i = 0; i < raw.length() && clean.length() < 90; i++) {
            char c = raw.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (valid) {
                clean.append(c);
                lastDash = false;
            } else if (!lastDash && clean.length() > 0) {
                clean.append('-');
                lastDash = true;
            }
        }
        String name = clean.toString();
        while (name.endsWith("-")) name = name.substring(0, name.length() - 1);
        return name;
    }

    private String plain(String value) {
        return DiscordClient.plain(value);
    }
}
