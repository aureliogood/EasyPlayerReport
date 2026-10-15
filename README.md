# EasyPlayerReport

Plugin de reportes para **Paper 1.21.7 o superior** y Java 21+. Los jugadores usan la Dialog API nativa de Paper y el staff gestiona los reportes en diálogos paginados. El JAR es ligero; Paper descarga SQLite y MySQL como librerías administradas por el servidor.

Avisa a un canal de Discord cuando alguien crea un reporte, crea **un canal por reporte** con el chat sincronizado en los dos sentidos, y avisa en el chat cuando hay una versión nueva subida.

## Instalar

Descarga el JAR de la sección [Releases](../../releases) y cópialo en `plugins/`.
Requerimientos: **Paper 1.21.7+** con **Java 21+**.

## Compilar desde el código

Necesitas un **JDK 21**. Con el JDK 25 el ofuscado falla: ProGuard todavía no
sabe leer los class files de la versión 69.

En Windows, `build.bat` lo hace todo (descarga Maven si no lo encuentra):

```bat
build.bat
```

En Linux o macOS:

```bash
mvn clean package
```

Las tres cosas que pasan, en orden:

1. **Pruebas.** 17 pruebas unitarias. Si una falla, no se genera el JAR.
2. **ProGuard** renombra las clases y elimina el código muerto (`proguard.pro`).
3. **Empaquetado.** El JAR sale en `target/EasyPlayerReport.jar`.

Y ya está: `target/proguard_map.txt` traduce los nombres ofuscados a los
originales. Sirve para entender un stack trace de producción, pero es la pieza
que deshace el ofuscado, así que está en el `.gitignore` y no debe subirse a
ningún sitio.

## Estructura

```
src/main/java/com/easyplayerreport/
  EasyPlayerReport      clase principal, registro de comandos y tareas
  ReportDialog          formulario de crear un reporte (Dialog API)
  StaffDialog           gestión de reportes para el staff
  ReportManager         estados, cooldowns, historial y reglas
  Report                un reporte
  SqlStore / YamlStore  persistencia en SQLite/MySQL y en YAML
  Discord*              avisos y canales por reporte
  UpdateChecker         aviso de versión nueva
src/main/resources/
  config.yml            opciones del plugin
  menus.yml             textos de los diálogos
  discord/discord.yml   todo lo de Discord (apagado por defecto)
  update.yml            aviso de actualizaciones
  lang/lang_es.yml      mensajes en español
  lang/lang_en.yml      mensajes en inglés
src/test/java/          las pruebas unitarias
```

No hay ninguna dependencia de terceros en el JAR. Discord y el aviso de
actualizaciones usan `java.net.http`, que ya viene en Java.

## Compatibilidad

- **Requiere Paper 1.21.7 o posterior**: la Paper Dialog API se publicó en la 1.21.7,
  que es el primer servidor capaz de enseñar los formularios.
- **No funciona en Spigot, CraftBukkit ni Paper 1.21.6 o anterior.** Si falta la Dialog
  API el plugin se deshabilita solo y escribe en consola un aviso claro, sin romper nada.
- **Arranque comprobado** en **1.21.11**: el plugin habilita sin errores y el
  remapeo de Paper digiere el JAR ofuscado.
- **17 pruebas unitarias** en `mvn package`, sobre la lógica que no necesita
  servidor: la comparación de versiones del aviso de actualización, el parser de
  JSON que usan los avisos de Discord y la limpieza de nombres de canal. Si una
  falla, no se genera el JAR. Lo que **no** cubren es el resto: diálogos,
  persistencia y moderación siguen comprobándose a mano en un servidor.
- Compilado en **bytecode Java 21**, así que sirve en cualquier Paper que corra con
  Java 21 o superior.
- El `api-version` es `1.21`, que Paper acepta en todas las versiones actuales.

## Si venías de OdisseyReports

El plugin se renombró. Al arrancar por primera vez busca la carpeta antigua
`plugins/OdisseyReports/` y, si la nueva `plugins/EasyPlayerReport/` está vacía,
copia dentro la configuración, los idiomas y la base de datos. Verás un aviso en
la consola y la carpeta antigua **no se borra**: es tu copia de seguridad. Bórrala
cuando confirmes que los reportes y la configuración están bien.

Si la carpeta nueva ya tenía algo, no se toca nada.

Para quien venga de la 1.0.0 en SpigotMC: **no hay que hacer nada**. Las columnas
nuevas de la base de datos y las claves nuevas de los archivos de configuración se
añaden solas al arrancar, y en consola te dirá exactamente qué se añadió.

Ojo con los permisos, que sí cambian de nombre. Tienes que reasignarlos en
LuckPerms:

| Antes | Ahora |
|---|---|
| `odisseyreports.use` | `easyplayerreport.use` |
| `odisseyreports.staff` | `easyplayerreport.staff` |
| `odisseyreports.moderate` | `easyplayerreport.moderate` |
| `odisseyreports.falsereport` | `easyplayerreport.falsereport` |
| `odisseyreports.admin` | `easyplayerreport.admin` |
| `odisseyreports.history` | `easyplayerreport.history` |
| `odisseyreports.stats` | `easyplayerreport.stats` |
| `odisseyreports.export` | `easyplayerreport.export` |
| `odisseyreports.reopen` | `easyplayerreport.reopen` |
| `odisseyreports.appeal` | `easyplayerreport.appeal` |

El comando `/odisseyreports` pasó a ser `/easyplayerreport`.

## Instalación

1. Copia `EasyPlayerReport.jar` en `plugins/`.
2. Inicia Paper 1.21.7 o superior con Java 21 o superior.
3. Ajusta `plugins/EasyPlayerReport/config.yml`, `menus.yml` y `lang/lang_es.yml` si es necesario.
4. Recarga con `/easyplayerreport reload` o reinicia el servidor.
5. Opcional: rellena `update.yml` (aviso de actualizaciones) y `discord/discord.yml` (avisos y tickets).

Los dos archivos opcionales se crean solos la primera vez, y ambos se pueden dejar apagados sin tocar nada: `check-update: false` en `config.yml` y `enabled: false` en `discord/discord.yml`.

### Actualizar sin romper nada

Minecraft no pisa un archivo de configuración que ya existe. Eso significaba que al subir una versión con textos u opciones nuevas, los jugadores que ya la tenían se quedaban con el archivo viejo y **veían renglones en blanco** donde debían aparecer los mensajes nuevos, sin un solo error en consola.

Ahora, al arrancar, el plugin compara `config.yml`, `menus.yml` y los archivos de idioma con los del JAR y **rellena solo las claves que falten**, sin tocar lo que el jugador haya personalizado. En consola verás una línea informativa:

```
Se añadieron 10 textos nuevos a lang_es.yml: messages.chat-discord-1, ...
```

Pasa una sola vez por clave: en los arranques siguientes ya no se toca nada.

## Comandos

| Comando | Uso |
|---|---|
| `/reportar` | Abre el formulario nativo para crear un reporte. |
| `/reportes` | Muestra tus reportes relacionados. |
| `/reportesadmin` | Abre la lista de gestión de staff. |
| `/reportechat <id>` | Abre el chat privado del reporte. |
| `/reportestoggle` | Activa o desactiva las notificaciones de reportes. |
| `/apelacion <id>` | Apela un reporte propio en el que eres el reportado. |
| `/reporteshistorial <jugador>` o `/historial <jugador>` | Abre el historial de reportes de un nombre o UUID. |
| `/reportesestadisticas` | Abre el resumen de estadísticas. |
| `/easyplayerreport reload` | Recarga configuración, textos e idiomas. |
| `/easyplayerreport stats` | Abre las mismas estadísticas desde el comando administrativo. |
| `/easyplayerreport export <json\|csv>` | Exporta los reportes a `plugins/EasyPlayerReport/exports/`. |

## Aviso de actualizaciones

Con `check-update: true` en `config.yml` (el valor por defecto) el plugin pregunta a la API de SpigotMC si hay una versión más nueva y, cuando un jugador entra, le escribe en el chat un recuadro con la versión y un enlace para descargarla:

```
--------------------------------------------
EasyPlayerReport Update Available:
A new version is available: 1.2.0
Click here to download the update.
--------------------------------------------
```

Para que funcione hay que rellenar `update.yml`:

| Clave | Qué es |
|---|---|
| `resource-id` | El número al final de la URL de tu página en SpigotMC: `spigotmc.org/resources/tu-plugin.12345/` → `12345` |
| `server-id` | El GUID del servidor, en el `console.txt` de Paper, en una línea `server-id: 6f9a...` |
| `notify-permission` | Quién ve el aviso. Por defecto `easyplayerreport.staff` |
| `check-interval-seconds` | Cada cuánto se pregunta. Por defecto 21600 (6 horas) |
| `url` | Si lo rellenas, se pregunta a tu API en vez de a SpigotMC. Debe devolver `{"version": "1.2.0"}` |
| `download-url` | Enlace del "descargar la actualización". Si está vacío se construye con `resource-id` |

Detalles que importan:

- **Entrar al servidor no hace ninguna petición de red.** La comprobación es una tarea programada por `check-interval-seconds` y el resultado se guarda; al entrar solo se lee lo que ya estaba cacheado.
- Solo lo ve quien tenga `notify-permission`, y **una vez por sesión**: si el jugador se reconecta no se le repite.
- Con `check-update: false` no se hace ni una petición. Con `enabled: false` dentro de `update.yml` tampoco.
- Si no hay red, o la respuesta no se entiende, o faltan `resource-id` y `server-id`, el plugin **sigue funcionando igual y no dice nada**. Esto es una comodidad, nunca una dependencia.
- Las versiones se comparan por partes (`1.10` es mayor que `1.9`, cosa que un comparador de texto no pilla).
- El texto del aviso está en `lang/lang_es.yml` y `lang/lang_en.yml` bajo `messages.update-*`, así que se pueden cambiar colores, palabras o idioma sin tocar el código.

## Discord

Todo lo de Discord vive en `discord/discord.yml`, que se crea sozinho la primera vez y **arranca apagado**. No modifica `config.yml`.

Son dos cosas independientes:

### 1. Avisos al canal

Un embed por evento, con su interruptor en `notify-on`:

| Evento | Qué manda |
|---|---|
| `created` | Reportado por / reportado a / estado / motivo / lugar y lo que escribió el jugador. Es el único que hace ping |
| `taken` | Quién lo tomó y cuántos pendientes quedan |
| `closed` | Quién cerró, cuánto duró abierto y la nota privada del staff |
| `reopened` | Quién reabrió |
| `appeal` | La apelación textual |
| `moderation` | La acción (kick/warn/ban) y por quién |
| `false-report` | Quién lo marcó y a quién bloquea |

Se configuran el canal (`channel-id`), el bot (`token`), el ping (`ping-role-id`, `ping-here`), el color y el avatar del embed.

### 2. Tickets: un canal por reporte

Cuando **un miembro del staff toma un reporte**, el plugin crea un canal en la categoría configurada (`reporte-142`) con un resumen dentro, y a partir de ahí el chat corre en los dos sentidos:

- lo que se escribe en el chat del diálogo aparece en el canal;
- lo que se escribe en el canal aparece en el chat del diálogo en unos segundos.

Al cerrar, el canal **se archiva** (no se borra): el chat queda como registro pero sin poder escribir. Si el reporte se reabre, se desarchiva.

**Cómo funciona sin montar nada:** no usa JDA ni la Gateway de Discord, que exigirían una conexión permanente y puertos abiertos. Usa solo la API REST con `java.net.http`, que ya viene en Java: al crear el canal desde el propio plugin no hace falta ningún botón, y para el retorno el bot lee el historial con `GET /channels/{id}/messages`. Cero librerías nuevas, el JAR no crece, y todo va asíncrono.

El precio es que hay unos segundos de retardo y no se ve el "está escribiendo". El id del canal se guarda con el reporte, así que sobrevive a reinicios.

**Permisos que necesita el bot** en Discord: ver el canal, enviar mensajes, **leer el historial**, **gestionar canales** y **incrustar enlaces**. Con solo "enviar mensajes" los avisos funcionan pero los tickets no.

El bot no necesita Gateway Intents, porque no se conecta a la Gateway.

## Reportes y estados

Un reporte conserva mundo, coordenadas, jugador reportado, motivo, descripción, estado, nota privada, historial de acciones y chat. Las coordenadas se cargan directamente del almacén aunque el mundo todavía no esté disponible; el teleport usa la posición guardada cuando el mundo esté cargado.

Los estados son `OPEN`, `TAKEN` y `CLOSED`. El staff puede tomar, liberar, reasignar a sí mismo, cerrar, reabrir cerrados con `easyplayerreport.reopen`, teleportarse al reporte o reportado, abrir el chat y a\u00f1adir una nota privada. Cada reapertura valida de nuevo el permiso y el estado, registra `REOPENED` en el historial, reinicia el plazo de auto-cierre y notifica al reportero. Un reportado puede abrir `Apelar Reporte` desde sus reportes recibidos, escribir una descripción y enviarla; puede hacerlo tanto con el reporte abierto como cerrado, y una sola vez por reporte. `appeal-cooldown-seconds` limita cada cuántos segundos puede apelar, sin importar el reporte. `reopen-cooldown-seconds` permite limitar reaperturas repetidas. La eliminación requiere `easyplayerreport.admin`. El diálogo de staff permite filtrar por `OPEN`, `TAKEN`, `CLOSED` y `ALL`, cambiar de página y consultar los tres estados.

El chat solo se habilita cuando el reporte tiene staff asignado. Solo el reportero y el staff asignado pueden enviar mensajes. El historial se guarda en el almacén configurado y en el respaldo del reporte cerrado, hasta el límite configurado.

Con `easyplayerreport.moderate` el staff puede ejecutar kick, warn o ban sobre el reportado desde el detalle. Cada acción queda registrada en el historial. El estado de `/reportestoggle` se guarda por UUID: al desactivarlo, el staff deja de recibir alertas, sonidos y mensajes automáticos, pero puede seguir usando `/reportesadmin`.

Con `easyplayerreport.falsereport` el staff puede marcar un reporte como falso. La acción pide confirmación, cierra el reporte, registra `FALSE_REPORT` en el historial y bloquea los reportes de quien lo creó durante `false-report-block-seconds` (3600 por defecto, una hora). Mientras dure el bloqueo, `/reportar` responde *"Hiciste un reporte falso, espera X para volver a reportar"*, tanto al abrir el formulario como al enviarlo. Es la defensa contra el spam de reportes, que el cooldown normal no cubre: este solo obliga a esperar 60 segundos, así que un jugador molesto podría abrir los tres reportes activos seguidos. Con el valor en 0 el plugin solo avisa, sin bloquear.

Todos los tiempos, límites y efectos son configurables. Las duraciones van en segundos, los efectos de poción en ticks (20 ticks = 1 segundo) y las duraciones de ban aceptan `30d`, `12h`, `45m`, `1d12h30m`, un número suelto o `permanent`. Los motivos sin emoji reciben el icono que corresponda según la sección `reason-icons`, así que se pueden escribir sin emojis en `reasons` y el plugin los adorna. Cualquier valor por debajo de 1 en `limits` se trata como 1, porque la Dialog API no admite 0.

## Persistencia y retención

La persistencia usa una capa de almacenamiento configurable. `database.type` acepta `sqlite` (valor por defecto) o `mysql`. SQLite crea `plugins/EasyPlayerReport/reports.db`; MySQL usa `host`, `port`, `database`, `user` y `password` de `config.yml`. Las contraseñas no se escriben en logs.

Al activar SQLite o MySQL, el plugin comprueba una marca de migración en la propia base de datos. Si la marca no existe, migra una sola vez desde `reports.yml` (o desde una lista vacía si no hay archivo) reportes, chat, notas, historial, coordenadas, estados, expiración, cooldowns e IDs eliminados. La marca se confirma transaccionalmente con los datos y el YAML original no se borra. Si la base de datos no puede inicializarse, leerse o guardarse, el plugin registra el error y continúa usando YAML; una caída posterior de persistencia cambia automáticamente al fallback YAML. Las consultas usan parámetros preparados y se ejecutan de forma síncrona en el hilo principal de Bukkit.

El formato YAML v3 sigue soportado para migración y fallback, aceptando datos v2 y el formato legado existente. Al cerrar se crea o actualiza `backupreports/report_<id>.yml`. Las tareas de limpieza y expiración se ejecutan de forma síncrona en el hilo principal de Bukkit.

La retención no elimina reportes activos:

```yaml
chat-max-messages: 100
retention:
  closed-reports-days: 30
  backups-days: 30
```

Los valores `0` desactivan la retención correspondiente. La limpieza se ejecuta automáticamente.

## Permisos

| Permiso | Default | Uso |
|---|---|---|
| `easyplayerreport.use` | todos | Crear y consultar reportes. |
| `easyplayerreport.staff` | op | Gestionar reportes y teleportarse. |
| `easyplayerreport.moderate` | op | Kick, warn y ban del reportado. |
| `easyplayerreport.falsereport` | op | Marcar un reporte como falso y bloquear al reportador. |
| `easyplayerreport.admin` | op | Eliminar reportes y acceder a las funciones administrativas. |
| `easyplayerreport.history` | op | Consultar el historial por jugador. |
| `easyplayerreport.stats` | op | Consultar estadísticas. |
| `easyplayerreport.export` | op | Exportar reportes. |
| `easyplayerreport.reopen` | op | Reabrir reportes cerrados. |
| `easyplayerreport.appeal` | todos | Apelar reportes propios en los que eres el reportado. |

## Configuración principal

```yaml
cooldown-seconds: 60
max-active-per-player: 3
chat-max-lines: 10
chat-refresh-seconds: 20
chat-max-messages: 100
lang: es
auto-close-seconds: 43200
duplicate-window-minutes: 5
staff-alert-threshold: 5
sounds:
  enabled: true
reopen-cooldown-seconds: 0
appeal-cooldown-seconds: 300
false-report-block-seconds: 3600
limits:
  target-name-max-length: 16
  description-max-length: 120
  appeal-max-length: 500
  note-max-length: 500
  chat-message-max-length: 120
  reports-per-page: 6
  input-width: 310
effects:
  reported-blindness-ticks: 60
  reported-blindness-amplifier: 0
sounds:
  enabled: true
  reported-volume: 1.0
  reported-pitch: 1.5
  alert-volume: 1.0
  alert-pitch: 1.0
  alert-pitch-busy: 2.0
moderation:
  ban-duration: "7d"
  ban-ip: false
permission-config:
  enabled: false
  reason:
    "Acoso":
      use: true
      permissions: []
reasons:
  - "⚔️ Hacks / Cheats"
  - "💬 Chat / Spam / Toxicidad"
  - "🏰 Griefing / Robo"
  - "🐛 Bug / Duplicacion"
```

Los textos de botones, filtros, paginación, notas, reasignación, moderación, reapertura, historial, estadísticas y cerrados están en `menus.yml`. Los mensajes están en `lang/lang_es.yml` y `lang/lang_en.yml`.

`permission-config.enabled` activa reglas por permisos para motivos mediante `permission-config.reason.<motivo>.use`, además de overrides de `auto-close-seconds`, límites, cooldown y sonidos. Las listas `permissions` pueden contener uno o varios nodos; una entrada sin `permissions` aplica a todos cuando `use: true`. Con `enabled: false`, o sin una regla aplicable, se usa la configuración global. Los permisos personalizados de límites y sonidos se configuran dentro de esas listas.

Las exportaciones usan los datos actualmente cargados en memoria, no ejecutan SQL. JSON se serializa con el código estándar de Java y CSV escapa comillas, saltos de línea y fórmulas de hoja de cálculo. El timestamp y un sufijo incremental evitan sobrescribir archivos existentes.
