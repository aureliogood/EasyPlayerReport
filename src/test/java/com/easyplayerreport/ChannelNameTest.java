package com.easyplayerreport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discord rechaza con un error poco claro cualquier canal que no sea solo
 * minusculas, guiones y numeros. El motivo de un reporte trae acentos,
 * emojis y simbolos, asi que el nombre se construye casi siempre con algo que
 * hay que limpiar, y si se escapa un caracter el ticket simplemente no se crea.
 */
class ChannelNameTest {

    @Test
    void quitaLosAcentosSinDejarGuionesDeMas() {
        assertEquals("reporte-acosar", DiscordTickets.sanitizeChannelName("reporte-acosar"));
        assertEquals("accion", DiscordTickets.sanitizeChannelName("Acción"));
        assertEquals("duplicacion", DiscordTickets.sanitizeChannelName("Duplicación"));
    }

    @Test
    void convierteLosEmojisYSimbolosEnNada() {
        // El motivo por defecto trae un emoji delante.
        assertEquals("hacks-cheats", DiscordTickets.sanitizeChannelName("⚔️ Hacks / Cheats"));
        assertEquals("griefing-robo", DiscordTickets.sanitizeChannelName("🏰 Griefing / Robo"));
    }

    @Test
    void pasaTodoAMinusculasYSoloDejaGuiOnesSeparados() {
        assertEquals("reporte-142-steve", DiscordTickets.sanitizeChannelName("Reporte_142_Steve"));
        assertEquals("a-b", DiscordTickets.sanitizeChannelName("a   b"));
        assertEquals("a-b", DiscordTickets.sanitizeChannelName("a---b"));
        assertEquals("a-b", DiscordTickets.sanitizeChannelName("a - - - b"));
    }

    @Test
    void recortaALoQueAdmiteDiscordYQuitaLaCola() {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 200; i++) tooLong.append('x');
        assertEquals(90, DiscordTickets.sanitizeChannelName(tooLong.toString()).length());

        // Nada de terminar en guion: Discord lo rechaza.
        StringBuilder endingInDash = new StringBuilder("reporte");
        for (int i = 0; i < 40; i++) endingInDash.append(' ');
        assertTrue(!DiscordTickets.sanitizeChannelName(endingInDash.toString()).endsWith("-"));
    }

    @Test
    void noEmpiezaConGuionNiSeQuedaVacio() {
        assertEquals("reporte-1", DiscordTickets.sanitizeChannelName(" --- reporte 1"));
        // Si todo es simbolos no queda nada: el nombre cae al de por defecto.
        assertEquals("", DiscordTickets.sanitizeChannelName("⚔️🐛💬"));
        assertEquals("", DiscordTickets.sanitizeChannelName(""));
    }
}