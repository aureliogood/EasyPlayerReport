package com.easyplayerreport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La comparacion de versiones decide a quien se le avisa. Aqui estan los casos
 * que un simple &gt; sobre texto resolveria mal y por los que el staff recibiria
 * (o dejaria de recibir) el aviso de actualizacion.
 */
class UpdateCheckerTest {

    @Test
    void avisaCuandoLaRemotaEsMasNueva() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.0"));
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"));
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"));
    }

    @Test
    void noAceptaElDiezComoMenorQueElNueve() {
        // El fallo clasico: comparando texto, "1.9" > "1.10" porque "9" > "1".
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"));
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.10.0"));
    }

    @Test
    void unLanzamientoEsMasNuevoQueSuPrevia() {
        // Con "1.2.0" y "1.2.0-beta" como iguales, quienEste en la beta no
        // recibiria nunca el aviso de subir a la estable.
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.2.0-beta"));
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.2.0-SNAPSHOT"));
        assertFalse(UpdateChecker.isNewer("1.2.0-beta", "1.2.0"));
        // Pero un numero mas alto gana aunque lleve sufijo.
        assertTrue(UpdateChecker.isNewer("1.2.1-SNAPSHOT", "1.2.0"));
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.1-SNAPSHOT"));
    }

    @Test
    void aguantaVersionesConDistintoNumeroDePartes() {
        assertTrue(UpdateChecker.isNewer("1.2", "1.1.9"));
        // 1.2 y 1.2.0 son la misma version: no tiene sentido avisar.
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"));
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2"));
        assertEquals(0, UpdateChecker.compare("1.2", "1.2.0"));
    }

    @Test
    void conLaMismaVersionNoDiceNada() {
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"));
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"));
    }

    @Test
    void unaVersionRotaNoProvocaUnFalsoPositivo() {
        assertFalse(UpdateChecker.isNewer("", "1.0.0"));
        assertFalse(UpdateChecker.isNewer("abc", "1.0.0"));
        assertEquals(0, UpdateChecker.compare("1.0.0", "1.0.0"));
        assertTrue(UpdateChecker.compare("1.0.1", "1.0.0") > 0);
        assertTrue(UpdateChecker.compare("1.0.0", "1.0.1") < 0);
    }
}