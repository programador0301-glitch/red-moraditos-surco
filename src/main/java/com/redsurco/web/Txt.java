package com.redsurco.web;

import java.text.Normalizer;

/**
 * Utilidades de texto para nombres de grupos y cursos.
 *
 * - limpia(): la forma "bonita" que se guarda (sin espacios sobrantes).
 * - clave(): la forma para COMPARAR, tolerante a errores de tipeo: ignora
 *   mayúsculas/minúsculas, acentos y cualquier espacio (de más, dobles, o
 *   metidos entre letras). Así "DISCIPULADO 1", "Disciplado  1" y
 *   "DISCIPULAD O 1" se reconocen como el mismo y no se duplican.
 */
public final class Txt {

    private Txt() { }

    /** Quita espacios al inicio/fin y colapsa espacios internos a uno solo. */
    public static String limpia(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ");
    }

    /** Clave de comparación: minúsculas, sin acentos y sin ningún espacio. */
    public static String clave(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");   // elimina acentos
        return n.replaceAll("\\s+", "");      // elimina TODOS los espacios
    }
}
