package com.redsurco.web.importar;

import com.redsurco.web.dao.CursoDao;
import com.redsurco.web.dao.GrupoDao;
import com.redsurco.web.dao.PersonaDao;
import com.redsurco.web.model.Persona;

import java.sql.Connection;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Importa personas desde una lista (Excel o CSV) y ACTUALIZA la base:
 * agrega personas nuevas, actualiza datos de las existentes (por documento),
 * crea grupos y cursos que falten y agrega los cursos a cada persona.
 * Es aditivo: nunca borra personas ni cursos previos.
 */
public final class Importador {

    /** Resumen de lo importado, para mostrarlo al usuario. */
    public static final class Resultado {
        public int nuevos, actualizados, cursosAgregados, gruposCreados, filasIgnoradas;
        public final List<String> avisos = new ArrayList<>();
    }

    private Importador() { }

    public static Resultado importar(Connection con, List<List<String>> filas) {
        Resultado r = new Resultado();
        if (filas == null || filas.size() < 2) {
            r.avisos.add("El archivo no tiene filas de datos (se espera una fila de encabezados y luego los registros).");
            return r;
        }

        // Mapa de encabezado -> índice de columna
        Map<String, Integer> col = new java.util.HashMap<>();
        List<String> cab = filas.get(0);
        for (int i = 0; i < cab.size(); i++) col.put(norm(cab.get(i)), i);

        int cDoc = primera(col, "documento", "dni", "num documento", "nro documento", "n documento", "docid", "nro doc", "n doc");
        int cNom = primera(col, "nombres completos", "nombres", "nombre", "alumno", "apellidos y nombres");
        int cGru = primera(col, "grupo", "red");
        int cLid = primera(col, "lider", "líder");
        int cTip = primera(col, "tipo", "tipo documento", "tipo de documento");
        int cCel = primera(col, "celular", "telefono", "teléfono", "movil", "móvil", "cel");
        int cCor = primera(col, "correo", "email", "e-mail", "mail");
        int cCur = primera(col, "cursos estudiados", "cursos", "curso");
        int cRes = primera(col, "reset", "encuentro");

        if (cDoc < 0 || cNom < 0) {
            r.avisos.add("Faltan columnas obligatorias: se necesita 'Documento' y 'Nombres completos'. "
                    + "Encabezados encontrados: " + String.join(", ", cab));
            return r;
        }

        PersonaDao pdao = new PersonaDao(con);
        GrupoDao gdao = new GrupoDao(con);
        CursoDao cdao = new CursoDao(con);

        int gruposAntes = gdao.findAll().size();

        for (int i = 1; i < filas.size(); i++) {
            List<String> fila = filas.get(i);
            String doc = celda(fila, cDoc);
            String nombres = celda(fila, cNom);
            if (doc.isBlank()) { r.filasIgnoradas++; continue; }

            Persona existente = pdao.findByDoc(doc);
            if (existente == null && nombres.isBlank()) {
                r.filasIgnoradas++;
                r.avisos.add("Fila " + (i + 1) + ": documento " + doc + " sin nombres; se omitió.");
                continue;
            }

            // Grupo (crea si no existe)
            Integer grupoId = (existente == null) ? null : existente.grupoId;
            String grupoNombre = cGru >= 0 ? celda(fila, cGru) : "";
            String lider = cLid >= 0 ? celda(fila, cLid) : "";
            if (!grupoNombre.isBlank()) grupoId = gdao.findOrCreate(grupoNombre, lider);

            Persona p = (existente == null) ? new Persona() : existente;
            if (existente == null) p.numDocumento = doc;
            if (!nombres.isBlank()) p.nombresCompletos = nombres;
            if (cTip >= 0 && !celda(fila, cTip).isBlank()) p.tipoDocumento = tipoDe(celda(fila, cTip), doc);
            else if (existente == null) p.tipoDocumento = tipoPorLongitud(doc);
            if (cCel >= 0 && !celda(fila, cCel).isBlank()) p.celular = celda(fila, cCel);
            if (cCor >= 0 && !celda(fila, cCor).isBlank()) p.correo = celda(fila, cCor);
            p.grupoId = grupoId;
            if (cRes >= 0) p.reset = esSi(celda(fila, cRes));

            try {
                if (existente == null) { pdao.insert(p); r.nuevos++; }
                else { pdao.update(p); r.actualizados++; }
            } catch (RuntimeException e) {
                r.filasIgnoradas++;
                r.avisos.add("Fila " + (i + 1) + " (doc " + doc + "): no se pudo guardar (" + e.getMessage() + ").");
                continue;
            }

            // Cursos (aditivo)
            if (cCur >= 0) {
                for (String nom : celda(fila, cCur).split("[,;]")) {
                    String cn = nom.trim();
                    if (cn.isEmpty()) continue;
                    int cursoId = cdao.findOrCreate(cn);
                    if (cursoId > 0 && pdao.agregarCurso(p.id, cursoId)) r.cursosAgregados++;
                }
            }
        }

        r.gruposCreados = Math.max(0, gdao.findAll().size() - gruposAntes);
        return r;
    }

    // ---- utilidades ----

    private static int primera(Map<String, Integer> col, String... claves) {
        for (String k : claves) { Integer i = col.get(k); if (i != null) return i; }
        return -1;
    }

    private static String celda(List<String> fila, int i) {
        return (i >= 0 && i < fila.size() && fila.get(i) != null) ? fila.get(i).trim() : "";
    }

    /** Tipo según el texto de la columna "Tipo"; si no es claro, deduce por el número. */
    private static String tipoDe(String s, String doc) {
        String n = norm(s);
        if (n.contains("carne") || n.contains("extranjeria") || n.equals("ce")) return Persona.CE;
        if (n.contains("otro")) return Persona.OTRO;
        if (n.contains("dni")) return Persona.DNI;
        return tipoPorLongitud(doc);
    }

    /** DNI si son 8 dígitos, CE si son 9 dígitos, OTRO en cualquier otro caso. */
    private static String tipoPorLongitud(String doc) {
        if (doc.matches("\\d{8}")) return Persona.DNI;
        if (doc.matches("\\d{9}")) return Persona.CE;
        return Persona.OTRO;
    }

    private static boolean esSi(String s) {
        String n = norm(s);
        return n.equals("si") || n.equals("s") || n.equals("1") || n.equals("x")
                || n.equals("true") || n.equals("verdadero") || n.equals("sí");
    }

    /** Normaliza: minúsculas, sin acentos, espacios colapsados. */
    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.replaceAll("\\s+", " ").trim();
    }

    // ---- CSV ----

    /** Parsea texto CSV (separador coma o punto y coma, campos entre comillas) a filas. */
    public static List<List<String>> leerCsv(String texto) {
        char delim = detectarSeparador(texto);
        List<List<String>> filas = new ArrayList<>();
        List<String> fila = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean enComillas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (enComillas) {
                if (c == '"') {
                    if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') { campo.append('"'); i++; }
                    else enComillas = false;
                } else campo.append(c);
            } else {
                if (c == '"') enComillas = true;
                else if (c == delim) { fila.add(campo.toString()); campo.setLength(0); }
                else if (c == '\n') { fila.add(campo.toString()); campo.setLength(0); filas.add(fila); fila = new ArrayList<>(); }
                else if (c == '\r') { /* ignora */ }
                else campo.append(c);
            }
        }
        if (campo.length() > 0 || !fila.isEmpty()) { fila.add(campo.toString()); filas.add(fila); }
        return filas;
    }

    /** Separador: ';' si la primera línea tiene más ';' que ',', si no ','. */
    private static char detectarSeparador(String texto) {
        int fin = texto.indexOf('\n');
        String primera = fin < 0 ? texto : texto.substring(0, fin);
        long comas = primera.chars().filter(ch -> ch == ',').count();
        long puntos = primera.chars().filter(ch -> ch == ';').count();
        return puntos > comas ? ';' : ',';
    }
}
