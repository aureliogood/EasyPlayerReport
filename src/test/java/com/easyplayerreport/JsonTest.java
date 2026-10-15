package com.easyplayerreport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El parser de JSON es el punto donde se rompen los avisos de Discord: si
 * devuelve null por una respuesta inesperada, el aviso se pierde en silencio y
 * no hay ningun error visible. Estos casos fijan lo que tiene que soportar.
 */
class JsonTest {

    @Test
    void leeLaRespuestaDeLaApiDeSpigot() {
        Map<String, Object> json = Json.object("{\"success\":true,\"message\":\"\",\"code\":0,\"version\":\"1.2.3\"}");
        assertEquals("1.2.3", Json.string(json, "version"));
        assertEquals("true", String.valueOf(json.get("success")));
        assertEquals("0", String.valueOf(json.get("code")));
    }

    @Test
    void devuelveVacioEnUnaRespuestaDeErrorDeSpigot() {
        // SpigotMC contesta esto si el resource-id o el server-id no valen.
        Map<String, Object> json = Json.object("{\"success\":false,\"code\":1,\"message\":\"Server not found\"}");
        assertEquals("", Json.string(json, "version"));
    }

    @Test
    void noSeRompeConHtmlNiConBasura() {
        // Un 403 de Cloudflare devuelve HTML, no JSON.
        assertNull(Json.parse("<html><body>Forbidden</body></html>"));
        assertEquals("", Json.string(Json.object("<html>403</html>"), "version"));
        assertEquals("", Json.string(Json.object(""), "version"));
        assertEquals("", Json.string(Json.object("{no es json"), "version"));
        assertEquals("", Json.string(null, "version"));
    }

    @Test
    void escapaDeLosCaracteresQueRompenElPayload() {
        String payload = "{\"content\":\"linea1\\nlinea2 \\\"entrecomillado\\\" \\\\ barra\"}";
        Map<String, Object> json = Json.object(payload);
        String content = Json.string(json, "content");
        assertTrue(content.contains("\n"));
        assertTrue(content.contains("\"entrecomillado\""));
        assertTrue(content.contains("\\"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void leeListasDeMensajesDeDiscord() {
        String body = "[{\"id\":\"10\",\"content\":\"hola\",\"author\":{\"id\":\"7\",\"username\":\"staff\"}},"
                + "{\"id\":\"11\",\"content\":\"\",\"attachments\":[]}]";
        List<Object> messages = Json.list(body);
        assertEquals(2, messages.size());
        Map<String, Object> first = (Map<String, Object>) messages.get(0);
        assertEquals("10", Json.string(first, "id"));
        assertEquals("hola", Json.string(first, "content"));
        Map<String, Object> author = Json.child(first, "author");
        assertEquals("staff", Json.string(author, "username"));
    }

    @Test
    void unObjetoQueNoEsObjetoDevuelveVacio() {
        assertNotNull(Json.object("[1,2,3]"));
        assertEquals(0, Json.object("[1,2,3]").size());
        assertEquals(0, Json.list("{\"a\":1}").size());
    }
}