package com.redsurco.web.dao;

import com.redsurco.web.model.Persona;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PersonaDao {

    private final Connection con;
    public PersonaDao(Connection con) { this.con = con; }

    private static String nz(String s) { return s == null ? "" : s; }

    public List<Persona> findAll(String filtro) { return findAll(filtro, ""); }

    /** vista: "" (todas), "singrupo", "reset" (fueron al Reset), "sinreset", "sincurso". */
    public List<Persona> findAll(String filtro, String vista) {
        boolean f = filtro != null && !filtro.isBlank();
        List<String> cond = new ArrayList<>();
        cond.add("p.eliminado=0");
        if (f) cond.add("(p.num_documento LIKE ? OR p.nombres_completos LIKE ? OR p.correo LIKE ?)");
        if ("singrupo".equals(vista)) cond.add("p.grupo_id IS NULL");
        if ("reset".equals(vista)) cond.add("p.reset=1");
        if ("sinreset".equals(vista)) cond.add("p.reset=0");
        if ("porconectar".equals(vista)) cond.add("p.conectado=0 AND p.creado IS NOT NULL "
                + "AND substr(p.creado,1,10) >= " + com.redsurco.web.Db.dateShift(-7) + "");
        if ("vencidos".equals(vista)) cond.add("p.conectado=0 AND p.creado IS NOT NULL "
                + "AND substr(p.creado,1,10) < " + com.redsurco.web.Db.dateShift(-7) + "");
        if ("sincurso".equals(vista)) cond.add(
                "NOT EXISTS (SELECT 1 FROM persona_curso pc2 WHERE pc2.persona_id=p.id)");

        StringBuilder sql = new StringBuilder(
            "SELECT p.id, p.tipo_documento, p.num_documento, p.nombres_completos, p.celular, p.correo, "
          + "p.grupo_id, p.reset, p.conectado, p.observaciones, p.creado, p.sexo, "
          + "g.nombre grupo_nombre, g.lider grupo_lider, "
          + "COUNT(pc.curso_id) ncur, " + com.redsurco.web.Db.groupConcat("c.nombre", ", ") + " cursos "
          + "FROM persona p LEFT JOIN grupo g ON g.id=p.grupo_id "
          + "LEFT JOIN persona_curso pc ON pc.persona_id=p.id LEFT JOIN curso c ON c.id=pc.curso_id ");
        if (!cond.isEmpty()) sql.append("WHERE ").append(String.join(" AND ", cond)).append(" ");
                sql.append("GROUP BY p.id, g.nombre, g.lider ORDER BY p.nombres_completos");

        List<Persona> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            if (f) { String like = "%" + filtro.trim() + "%"; for (int i=1;i<=3;i++) ps.setString(i, like); }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Persona p = new Persona();
                    p.id = rs.getInt("id");
                    p.tipoDocumento = rs.getString("tipo_documento");
                    p.numDocumento = rs.getString("num_documento");
                    p.nombresCompletos = rs.getString("nombres_completos");
                    p.celular = rs.getString("celular");
                    p.correo = rs.getString("correo");
                    p.grupoId = rs.getObject("grupo_id") == null ? null : rs.getInt("grupo_id");
                    p.reset = rs.getInt("reset") == 1;
                    p.conectado = rs.getInt("conectado") == 1;
                    p.observaciones = nz(rs.getString("observaciones"));
                    p.creado = rs.getString("creado");
                    p.grupoNombre = nz(rs.getString("grupo_nombre"));
                    p.grupoLider = nz(rs.getString("grupo_lider"));
                    p.numCursos = rs.getInt("ncur");
                    p.cursosTexto = nz(rs.getString("cursos"));
                    p.sexo = colStr(rs, "sexo");
                    out.add(p);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Personas "ganadas" el fin de semana más reciente (mismo rango que la tarjeta del Resumen). */
    public List<Persona> findGanados() {
        String[] rango = com.redsurco.web.Fechas.finDeSemana();
        String sql =
            "SELECT p.id, p.tipo_documento, p.num_documento, p.nombres_completos, p.celular, p.correo, "
          + "p.grupo_id, p.reset, p.conectado, p.observaciones, p.creado, p.sexo, "
          + "g.nombre grupo_nombre, g.lider grupo_lider, "
          + "COUNT(pc.curso_id) ncur, " + com.redsurco.web.Db.groupConcat("c.nombre", ", ") + " cursos "
          + "FROM persona p LEFT JOIN grupo g ON g.id=p.grupo_id "
          + "LEFT JOIN persona_curso pc ON pc.persona_id=p.id LEFT JOIN curso c ON c.id=pc.curso_id "
          + "WHERE p.eliminado=0 AND p.creado IS NOT NULL AND p.creado >= ? AND p.creado < ? "
                   + "GROUP BY p.id, g.nombre, g.lider ORDER BY p.nombres_completos";
        List<Persona> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, rango[0]); ps.setString(2, rango[1]);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(leer(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Integrantes de un grupo (para el detalle del grupo). */
    public List<Persona> findByGrupo(int grupoId) {
        String sql =
            "SELECT p.id, p.tipo_documento, p.num_documento, p.nombres_completos, p.celular, p.correo, "
          + "p.grupo_id, p.reset, p.conectado, p.observaciones, p.creado, p.sexo, "
          + "g.nombre grupo_nombre, g.lider grupo_lider, "
          + "COUNT(pc.curso_id) ncur, " + com.redsurco.web.Db.groupConcat("c.nombre", ", ") + " cursos "
          + "FROM persona p LEFT JOIN grupo g ON g.id=p.grupo_id "
          + "LEFT JOIN persona_curso pc ON pc.persona_id=p.id LEFT JOIN curso c ON c.id=pc.curso_id "
                    + "WHERE p.eliminado=0 AND p.grupo_id=? GROUP BY p.id, g.nombre, g.lider ORDER BY p.nombres_completos";
        List<Persona> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, grupoId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(leer(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Todas las personas de un grupo identificado por su nombre/código (ej. "C187"). */
    public List<Persona> findByGrupoNombre(String nombre) {
        String sql =
            "SELECT p.id, p.tipo_documento, p.num_documento, p.nombres_completos, p.celular, p.correo, "
          + "p.grupo_id, p.reset, p.conectado, p.observaciones, p.creado, p.sexo, "
          + "g.nombre grupo_nombre, g.lider grupo_lider, "
          + "COUNT(pc.curso_id) ncur, " + com.redsurco.web.Db.groupConcat("c.nombre", ", ") + " cursos "
          + "FROM persona p LEFT JOIN grupo g ON g.id=p.grupo_id "
          + "LEFT JOIN persona_curso pc ON pc.persona_id=p.id LEFT JOIN curso c ON c.id=pc.curso_id "
          + "WHERE p.eliminado=0 AND g.nombre=? GROUP BY p.id, g.nombre, g.lider ORDER BY p.nombres_completos";
        List<Persona> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nombre);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(leer(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Lee una fila con todas las columnas del SELECT completo (reutilizable). */
    private Persona leer(ResultSet rs) throws SQLException {
        Persona p = new Persona();
        p.id = rs.getInt("id");
        p.tipoDocumento = rs.getString("tipo_documento");
        p.numDocumento = rs.getString("num_documento");
        p.nombresCompletos = rs.getString("nombres_completos");
        p.celular = rs.getString("celular");
        p.correo = rs.getString("correo");
        p.grupoId = rs.getObject("grupo_id") == null ? null : rs.getInt("grupo_id");
        p.reset = rs.getInt("reset") == 1;
        p.conectado = rs.getInt("conectado") == 1;
        p.observaciones = nz(rs.getString("observaciones"));
        p.creado = rs.getString("creado");
        p.grupoNombre = nz(rs.getString("grupo_nombre"));
        p.grupoLider = nz(rs.getString("grupo_lider"));
        p.numCursos = rs.getInt("ncur");
        p.cursosTexto = nz(rs.getString("cursos"));
        p.sexo = colStr(rs, "sexo");
        return p;
    }

    /** Lee una columna de texto si existe en el ResultSet; "" si no está. */
    private static String colStr(ResultSet rs, String col) {
        try { return nz(rs.getString(col)); } catch (SQLException e) { return ""; }
    }

    /** Busca por número de documento (para la importación). */
    public Persona findByDoc(String num) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id, tipo_documento, num_documento, nombres_completos, celular, correo, grupo_id, reset "
              + "FROM persona WHERE num_documento=?")) {
            ps.setString(1, num);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Persona p = new Persona();
                p.id = rs.getInt("id");
                p.tipoDocumento = rs.getString("tipo_documento");
                p.numDocumento = rs.getString("num_documento");
                p.nombresCompletos = rs.getString("nombres_completos");
                p.celular = rs.getString("celular");
                p.correo = rs.getString("correo");
                p.grupoId = rs.getObject("grupo_id") == null ? null : rs.getInt("grupo_id");
                p.reset = rs.getInt("reset") == 1;
                return p;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Persona findById(int id) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id, tipo_documento, num_documento, nombres_completos, celular, correo, grupo_id, reset, "
              + "conectado, observaciones, creado, sexo FROM persona WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Persona p = new Persona();
                p.id = rs.getInt("id");
                p.tipoDocumento = rs.getString("tipo_documento");
                p.numDocumento = rs.getString("num_documento");
                p.nombresCompletos = rs.getString("nombres_completos");
                p.celular = rs.getString("celular");
                p.correo = rs.getString("correo");
                p.grupoId = rs.getObject("grupo_id") == null ? null : rs.getInt("grupo_id");
                p.reset = rs.getInt("reset") == 1;
                p.conectado = rs.getInt("conectado") == 1;
                p.observaciones = nz(rs.getString("observaciones"));
                p.creado = rs.getString("creado");
                p.sexo = colStr(rs, "sexo");
                return p;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public int insert(Persona p) {
        String sql = "INSERT INTO persona(tipo_documento,num_documento,nombres_completos,celular,correo,"
                   + "grupo_id,reset,conectado,observaciones,sexo,creado,registrado_por) "
                   + "VALUES(?,?,?,?,?,?,?,?,?,?," + com.redsurco.web.Db.now() + ",?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, p);
            if (p.registradoPor == null || p.registradoPor.isBlank()) ps.setNull(11, Types.VARCHAR);
            else ps.setString(11, p.registradoPor);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { if (k.next()) p.id = k.getInt(1); }
            return p.id;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void update(Persona p) {
        String sql = "UPDATE persona SET tipo_documento=?,num_documento=?,nombres_completos=?,celular=?,correo=?,"
                   + "grupo_id=?,reset=?,conectado=?,observaciones=?,sexo=? WHERE id=?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, p);
            ps.setInt(11, p.id);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Borrado SUAVE: manda la persona a la papelera (no se elimina de verdad). */
    public void delete(int id) { eliminarSuave(id, null); }

    public void eliminarSuave(int id, String usuario) {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE persona SET eliminado=1, eliminado_en=" + com.redsurco.web.Db.now() + ", eliminado_por=? WHERE id=?")) {
            if (usuario == null) ps.setNull(1, Types.VARCHAR); else ps.setString(1, usuario);
            ps.setInt(2, id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void restaurar(int id) {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE persona SET eliminado=0, eliminado_en=NULL, eliminado_por=NULL WHERE id=?")) {
            ps.setInt(1, id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Borrado DEFINITIVO (desde la papelera): elimina de verdad. */
    public void eliminarDefinitivo(int id) {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM persona WHERE id=?")) {
            ps.setInt(1, id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Personas en la papelera (eliminadas), más reciente primero. */
    public List<Persona> papelera() {
        String sql =
            "SELECT p.id, p.tipo_documento, p.num_documento, p.nombres_completos, p.celular, p.correo, "
          + "p.grupo_id, p.reset, p.conectado, p.observaciones, p.creado, p.eliminado_en, p.eliminado_por, "
          + "g.nombre grupo_nombre, g.lider grupo_lider, "
          + "COUNT(pc.curso_id) ncur, " + com.redsurco.web.Db.groupConcat("c.nombre", ", ") + " cursos "
          + "FROM persona p LEFT JOIN grupo g ON g.id=p.grupo_id "
          + "LEFT JOIN persona_curso pc ON pc.persona_id=p.id LEFT JOIN curso c ON c.id=pc.curso_id "
                   + "WHERE p.eliminado=1 GROUP BY p.id, g.nombre, g.lider ORDER BY p.eliminado_en DESC";
        List<Persona> out = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Persona p = leer(rs);
                p.eliminadoEn = nz(rs.getString("eliminado_en"));
                p.eliminadoPor = nz(rs.getString("eliminado_por"));
                out.add(p);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Personas registradas por un usuario (las que "ganó"), más reciente primero. */
    public List<Persona> registradasPor(String usuario) {
        String sql =
            "SELECT p.id, p.tipo_documento, p.num_documento, p.nombres_completos, p.celular, p.correo, "
          + "p.grupo_id, p.reset, p.conectado, p.observaciones, p.creado, p.sexo, "
          + "g.nombre grupo_nombre, g.lider grupo_lider, "
          + "COUNT(pc.curso_id) ncur, " + com.redsurco.web.Db.groupConcat("c.nombre", ", ") + " cursos "
          + "FROM persona p LEFT JOIN grupo g ON g.id=p.grupo_id "
          + "LEFT JOIN persona_curso pc ON pc.persona_id=p.id LEFT JOIN curso c ON c.id=pc.curso_id "
          + "WHERE p.eliminado=0 AND p.registrado_por=? "
                   + "GROUP BY p.id, g.nombre, g.lider ORDER BY p.creado DESC, p.nombres_completos";
        List<Persona> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, usuario);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(leer(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Personas inscritas en un curso (para ver quiénes del Resumen). */
    public List<Persona> findByCurso(int cursoId) {
        String sql =
            "SELECT p.id, p.tipo_documento, p.num_documento, p.nombres_completos, p.celular, p.correo, "
          + "p.grupo_id, p.reset, p.conectado, p.observaciones, p.creado, p.sexo, "
          + "g.nombre grupo_nombre, g.lider grupo_lider, "
          + "COUNT(pc.curso_id) ncur, " + com.redsurco.web.Db.groupConcat("c.nombre", ", ") + " cursos "
          + "FROM persona p LEFT JOIN grupo g ON g.id=p.grupo_id "
          + "LEFT JOIN persona_curso pc ON pc.persona_id=p.id LEFT JOIN curso c ON c.id=pc.curso_id "
          + "WHERE p.eliminado=0 AND EXISTS (SELECT 1 FROM persona_curso x WHERE x.persona_id=p.id AND x.curso_id=?) "
                   + "GROUP BY p.id, g.nombre, g.lider ORDER BY p.nombres_completos";
        List<Persona> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, cursoId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(leer(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private void bind(PreparedStatement ps, Persona p) throws SQLException {
        ps.setString(1, p.tipoDocumento == null ? Persona.DNI : p.tipoDocumento);
        setOrNull(ps, 2, p.numDocumento);
        ps.setString(3, p.nombresCompletos);
        setOrNull(ps, 4, p.celular);
        setOrNull(ps, 5, p.correo);
        if (p.grupoId != null) ps.setInt(6, p.grupoId); else ps.setNull(6, Types.INTEGER);
        ps.setInt(7, p.reset ? 1 : 0);
        ps.setInt(8, p.conectado ? 1 : 0);
        setOrNull(ps, 9, p.observaciones);
        setOrNull(ps, 10, p.sexo);
    }

    // ---- registro de cambios de seguimiento ----

    public void addLog(int personaId, String usuario, String accion, String nota) {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO seguimiento_log(persona_id,usuario,accion,nota,fecha) "
              + "VALUES(?,?,?,?," + com.redsurco.web.Db.now() + ")")) {
            ps.setInt(1, personaId);
            ps.setString(2, usuario);
            ps.setString(3, accion);
            ps.setString(4, nota);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Historial de seguimiento de una persona (más reciente primero): [fecha, usuario, accion, nota]. */
    public List<String[]> logsDe(int personaId) {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT fecha, usuario, accion, nota FROM seguimiento_log WHERE persona_id=? ORDER BY id DESC")) {
            ps.setInt(1, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new String[]{
                        nz(rs.getString("fecha")), nz(rs.getString("usuario")),
                        nz(rs.getString("accion")), nz(rs.getString("nota"))});
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private void setOrNull(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null || v.isBlank()) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v.trim());
    }

    public List<Integer> cursosDe(int personaId) {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT curso_id FROM persona_curso WHERE persona_id=?")) {
            ps.setInt(1, personaId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return ids;
    }

    /** Agrega un curso a la persona si no lo tenía. Devuelve true si lo agregó. */
    public boolean agregarCurso(int personaId, int cursoId) {
        try (PreparedStatement ps = con.prepareStatement(
                com.redsurco.web.Db.insertIgnore("persona_curso", "persona_id,curso_id", "?,?"))) {
            ps.setInt(1, personaId);
            ps.setInt(2, cursoId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void setCursos(int personaId, List<Integer> cursoIds) {
        try {
            con.setAutoCommit(false);
            try (PreparedStatement del = con.prepareStatement("DELETE FROM persona_curso WHERE persona_id=?")) {
                del.setInt(1, personaId); del.executeUpdate();
            }
            try (PreparedStatement ins = con.prepareStatement("INSERT INTO persona_curso(persona_id,curso_id) VALUES(?,?)")) {
                for (int cid : cursoIds) { ins.setInt(1, personaId); ins.setInt(2, cid); ins.addBatch(); }
                ins.executeBatch();
            }
            con.commit();
        } catch (SQLException e) {
            try { con.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException(e);
        } finally {
            try { con.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }
}
