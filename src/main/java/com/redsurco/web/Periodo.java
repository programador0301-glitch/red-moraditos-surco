package com.redsurco.web;

/** Utilidades de periodo (año-trimestre, ej. "2026-3") y sexo por código de grupo. */
public final class Periodo {
    private Periodo() {}

    /** Normaliza un texto de periodo a "YYYY-T" (T=1..4). Devuelve "" si no es válido. */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.trim().replace(" ", "");
        // admite "2026-3", "2026_3", "2026/3", "2026-03"
        t = t.replace('_', '-').replace('/', '-');
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{4})-0?([1-4])$").matcher(t);
        if (!m.matches()) return "";
        return m.group(1) + "-" + m.group(2);
    }

    /** Periodo inmediatamente anterior ("2026-1" -> "2025-4"). Vacío si no es válido. */
    public static String anterior(String periodo) {
        String p = norm(periodo);
        if (p.isEmpty()) return "";
        int anio = Integer.parseInt(p.substring(0, 4));
        int t = Integer.parseInt(p.substring(5));
        if (t == 1) return (anio - 1) + "-4";
        return anio + "-" + (t - 1);
    }

    /** Año de un periodo ("2026-3" -> "2026"). */
    public static String anio(String periodo) {
        String p = norm(periodo);
        return p.isEmpty() ? "" : p.substring(0, 4);
    }

    /** Etiqueta amigable ("2026-3" -> "3er trim. 2026"). */
    public static String etiqueta(String periodo) {
        String p = norm(periodo);
        if (p.isEmpty()) return periodo == null ? "" : periodo;
        String[] ord = {"", "1er", "2do", "3er", "4to"};
        int t = Integer.parseInt(p.substring(5));
        return ord[t] + " trim. " + p.substring(0, 4);
    }

    /** Equipo según el código: lleva 'J' (ej. C18J1, C28J2) = Next; si no, Principal. */
    public static boolean esNext(String codigo) {
        return codigo != null && codigo.trim().toUpperCase().indexOf('J') >= 0;
    }

    /**
     * Sexo deducido del código del grupo: C1x = Hombres, C2x = Mujeres.
     * Devuelve "H", "M" o "" (desconocido).
     */
    public static String sexoDeCodigo(String codigo) {
        if (codigo == null) return "";
        String c = codigo.trim().toUpperCase();
        if (c.length() >= 2 && c.charAt(0) == 'C') {
            char d = c.charAt(1);
            if (d == '1') return "H";
            if (d == '2') return "M";
        }
        return "";
    }
}
