package com.redsurco.web;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

/**
 * Acceso a la base de datos. Funciona con dos motores:
 *
 *  - PostgreSQL: cuando existe la variable de entorno DATABASE_URL (p. ej. Neon
 *    en Render). Es la base recomendada para la nube, porque los datos persisten.
 *  - SQLite (archivo redsurco.db): si NO hay DATABASE_URL. Útil para correr local.
 *
 * Se abre UNA conexión por petición (y se cierra al terminar).
 */
public final class Db {

    /** true = PostgreSQL (hay DATABASE_URL); false = SQLite local. */
    public static final boolean PG;
    private static final String URL, USER, PASS;
    /** Zona horaria para las fechas en PostgreSQL (Render/Neon corren en UTC). */
    private static final String TZ = "America/Lima";

    static {
        String env = primeraEnv("DATABASE_URL", "JDBC_DATABASE_URL", "POSTGRES_URL", "NEON_DATABASE_URL");
        if (env != null && !env.isBlank()) {
            PG = true;
            String[] c = parsePostgres(env.trim());
            URL = c[0]; USER = c[1]; PASS = c[2];
        } else {
            PG = false;
            URL = "jdbc:sqlite:redsurco.db"; USER = null; PASS = null;
        }
    }

    private Db() { }

    private static String primeraEnv(String... claves) {
        for (String k : claves) { String v = System.getenv(k); if (v != null && !v.isBlank()) return v; }
        return null;
    }

    /** Convierte una URL de Neon/Postgres a URL JDBC + usuario + contraseña. */
    private static String[] parsePostgres(String raw) {
        if (raw.startsWith("jdbc:")) {
            String u = raw;
            if (!u.contains("sslmode=")) u += (u.contains("?") ? "&" : "?") + "sslmode=require";
            return new String[]{ u, null, null };
        }
        String s = raw.replaceFirst("^postgres(ql)?://", "");
        String user = null, pass = null, hostpart = s;
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            String creds = s.substring(0, at);
            hostpart = s.substring(at + 1);
            int col = creds.indexOf(':');
            if (col >= 0) { user = dec(creds.substring(0, col)); pass = dec(creds.substring(col + 1)); }
            else user = dec(creds);
        }
        String url = "jdbc:postgresql://" + hostpart;
        if (!url.contains("sslmode=")) url += (url.contains("?") ? "&" : "?") + "sslmode=require";
        return new String[]{ url, user, pass };
    }

    private static String dec(String s) {
        try { return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    /** Abre una conexión nueva (quien la pide debe cerrarla con try-with-resources). */
    public static Connection open() {
        try {
            Connection con = (USER != null) ? DriverManager.getConnection(URL, USER, PASS)
                                            : DriverManager.getConnection(URL);
            if (!PG) {
                try (Statement st = con.createStatement()) {
                    st.execute("PRAGMA foreign_keys = ON");
                    st.execute("PRAGMA journal_mode = WAL");
                    st.execute("PRAGMA busy_timeout = 5000");
                }
            }
            return con;
        } catch (SQLException e) {
            throw new RuntimeException("No se pudo abrir la base de datos: " + e.getMessage(), e);
        }
    }

    // ---------- Helpers de dialecto (SQL que cambia entre SQLite y Postgres) ----------

    /** Clave primaria autoincremental. */
    private static String pk() { return PG ? "id SERIAL PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT"; }

    /** Expresión SQL: fecha y hora actual como texto 'YYYY-MM-DD HH:MM:SS'. */
    public static String now() {
        return PG ? "to_char((now() AT TIME ZONE '" + TZ + "'),'YYYY-MM-DD HH24:MI:SS')"
                  : "datetime('now','localtime')";
    }

    /** Expresión SQL: fecha de hoy +/- días, como texto 'YYYY-MM-DD'. */
    public static String dateShift(int dias) {
        return PG ? "to_char(((now() AT TIME ZONE '" + TZ + "')::date + (" + dias + ")),'YYYY-MM-DD')"
                  : "date('now','localtime','" + dias + " days')";
    }

    /** Agregado de concatenación (GROUP_CONCAT / string_agg). */
    public static String groupConcat(String expr, String sep) {
        return PG ? "string_agg(" + expr + ", '" + sep + "')" : "GROUP_CONCAT(" + expr + ", '" + sep + "')";
    }

    /** Año-mes de una columna de fecha-texto ('YYYY-MM'). */
    public static String ym(String col) {
        return PG ? "to_char(" + col + "::timestamp,'YYYY-MM')" : "strftime('%Y-%m'," + col + ")";
    }

    /** Año de una columna de fecha-texto ('YYYY'). */
    public static String yYear(String col) {
        return PG ? "to_char(" + col + "::timestamp,'YYYY')" : "strftime('%Y'," + col + ")";
    }

    /** Clave año-semana ('YYYY-S##'). */
    public static String yWeekKey(String col) {
        return PG ? "to_char(" + col + "::timestamp,'IYYY')||'-S'||to_char(" + col + "::timestamp,'IW')"
                  : "strftime('%Y'," + col + ")||'-S'||strftime('%W'," + col + ")";
    }

    /** INSERT que ignora duplicados (requiere índice/clave única en la tabla). */
    public static String insertIgnore(String tabla, String columnas, String valores) {
        return PG ? "INSERT INTO " + tabla + "(" + columnas + ") VALUES(" + valores + ") ON CONFLICT DO NOTHING"
                  : "INSERT OR IGNORE INTO " + tabla + "(" + columnas + ") VALUES(" + valores + ")";
    }

    /** Crea las tablas que falten y aplica las migraciones de columnas. */
    public static void init() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS grupo (" + pk() + ", nombre TEXT NOT NULL UNIQUE, lider TEXT)",
            "CREATE TABLE IF NOT EXISTS curso (" + pk() + ", nombre TEXT NOT NULL UNIQUE, modalidad TEXT)",
            "CREATE TABLE IF NOT EXISTS persona (" + pk() + ", "
                + "tipo_documento TEXT NOT NULL DEFAULT 'DNI', num_documento TEXT, "
                + "nombres_completos TEXT NOT NULL, celular TEXT, correo TEXT, "
                + "grupo_id INTEGER REFERENCES grupo(id) ON DELETE SET NULL, "
                + "reset INTEGER NOT NULL DEFAULT 0, creado TEXT, "
                + "conectado INTEGER NOT NULL DEFAULT 0, observaciones TEXT)",
            "CREATE TABLE IF NOT EXISTS persona_curso (persona_id INTEGER NOT NULL "
                + "REFERENCES persona(id) ON DELETE CASCADE, curso_id INTEGER NOT NULL "
                + "REFERENCES curso(id) ON DELETE CASCADE, PRIMARY KEY (persona_id, curso_id))",
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_persona_num ON persona(num_documento)",
            "CREATE TABLE IF NOT EXISTS usuario (" + pk() + ", "
                + "username TEXT NOT NULL UNIQUE, salt TEXT NOT NULL, hash TEXT NOT NULL, "
                + "rol TEXT NOT NULL DEFAULT 'USUARIO')",
            "CREATE TABLE IF NOT EXISTS seguimiento_log (" + pk() + ", "
                + "persona_id INTEGER NOT NULL REFERENCES persona(id) ON DELETE CASCADE, "
                + "usuario TEXT, accion TEXT, nota TEXT, fecha TEXT)",
            "CREATE TABLE IF NOT EXISTS meta (" + pk() + ", "
                + "periodo TEXT NOT NULL, codigo TEXT NOT NULL, nombre TEXT, "
                + "ins_lider INTEGER DEFAULT 0, ins_disc INTEGER DEFAULT 0, "
                + "res_lider INTEGER DEFAULT 0, res_disc INTEGER DEFAULT 0, "
                + "UNIQUE(periodo, codigo))"
        };
        try (Connection con = open(); Statement st = con.createStatement()) {
            for (String sql : ddl) st.execute(sql);
            if (!columnaExiste(con, "usuario", "rol"))
                st.execute("ALTER TABLE usuario ADD COLUMN rol TEXT NOT NULL DEFAULT 'ADMIN'");
            st.execute("UPDATE usuario SET rol='ADMIN' WHERE username='admin'");
            migraCol(con, st, "persona", "reset", "INTEGER NOT NULL DEFAULT 0");
            migraCol(con, st, "persona", "creado", "TEXT");
            migraCol(con, st, "persona", "conectado", "INTEGER NOT NULL DEFAULT 0");
            migraCol(con, st, "persona", "observaciones", "TEXT");
            migraCol(con, st, "persona", "eliminado", "INTEGER NOT NULL DEFAULT 0");
            migraCol(con, st, "persona", "eliminado_en", "TEXT");
            migraCol(con, st, "persona", "eliminado_por", "TEXT");
            migraCol(con, st, "usuario", "grupo", "TEXT");
            migraCol(con, st, "persona", "registrado_por", "TEXT");
            migraCol(con, st, "persona", "sexo", "TEXT");
        } catch (SQLException e) {
            throw new RuntimeException("No se pudo inicializar el esquema: " + e.getMessage(), e);
        }
    }

    private static void migraCol(Connection con, Statement st, String tabla, String col, String tipo) throws SQLException {
        if (!columnaExiste(con, tabla, col)) st.execute("ALTER TABLE " + tabla + " ADD COLUMN " + col + " " + tipo);
    }

    // ---------- Respaldo (solo SQLite; en la nube lo hace Neon automáticamente) ----------

    public static final String DIR_RESPALDOS = "backups";

    public static void respaldoDiario() {
        if (PG) return; // en Neon los respaldos son automáticos
        try {
            File dir = new File(DIR_RESPALDOS);
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, "redsurco-" + LocalDate.now() + ".db");
            if (dest.exists()) return;
            respaldarA(dest);
            limpiarAntiguos(dir, 30);
        } catch (Exception e) {
            System.err.println("Aviso: no se pudo crear el respaldo del día: " + e.getMessage());
        }
    }

    public static void respaldarA(File dest) {
        String ruta = dest.getPath().replace('\\', '/').replace("'", "''");
        try (Connection con = open(); Statement st = con.createStatement()) {
            st.execute("VACUUM INTO '" + ruta + "'");
        } catch (SQLException e) {
            throw new RuntimeException("No se pudo respaldar: " + e.getMessage(), e);
        }
    }

    private static void limpiarAntiguos(File dir, int conservar) {
        File[] fs = dir.listFiles((d, n) -> n.startsWith("redsurco-") && n.endsWith(".db"));
        if (fs == null || fs.length <= conservar) return;
        java.util.Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < fs.length - conservar; i++) fs[i].delete();
    }

    /** ¿Existe la columna? (PRAGMA en SQLite, information_schema en Postgres). */
    private static boolean columnaExiste(Connection con, String tabla, String columna) throws SQLException {
        if (PG) {
            try (var ps = con.prepareStatement(
                    "SELECT 1 FROM information_schema.columns WHERE table_name=? AND column_name=?")) {
                ps.setString(1, tabla.toLowerCase());
                ps.setString(2, columna.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        }
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabla + ")")) {
            while (rs.next()) if (columna.equalsIgnoreCase(rs.getString("name"))) return true;
        }
        return false;
    }
}
