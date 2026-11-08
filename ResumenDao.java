package com.redsurco.web.dao;

import com.redsurco.web.Db;
import com.redsurco.web.Fechas;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ResumenDao {
    private final Connection con;
    public ResumenDao(Connection con) { this.con = con; }

    public int totalPersonas() { return count("SELECT COUNT(*) FROM persona WHERE eliminado=0"); }
    public int totalCursos()   { return count("SELECT COUNT(*) FROM curso"); }
    public int totalGrupos()   { return count("SELECT COUNT(*) FROM grupo"); }
    public int personasSinCurso() {
        return count("SELECT COUNT(*) FROM persona p WHERE p.eliminado=0 AND NOT EXISTS "
                   + "(SELECT 1 FROM persona_curso pc WHERE pc.persona_id=p.id)");
    }

    /** Personas que aún NO han ido al Reset. */
    public int personasSinReset() {
        return count("SELECT COUNT(*) FROM persona WHERE eliminado=0 AND reset=0");
    }

    /** Pendientes de conectar y DENTRO del plazo de 7 días. */
    public int porConectar() {
        return count("SELECT COUNT(*) FROM persona WHERE eliminado=0 AND conectado=0 AND creado IS NOT NULL "
                   + "AND substr(creado,1,10) >= " + Db.dateShift(-7) + "");
    }

    /** Pendientes de conectar cuyo plazo de 7 días YA venció. */
    public int vencidosSinConectar() {
        return count("SELECT COUNT(*) FROM persona WHERE eliminado=0 AND conectado=0 AND creado IS NOT NULL "
                   + "AND substr(creado,1,10) < " + Db.dateShift(-7) + "");
    }

    /** "Ganados": personas registradas el fin de semana más reciente (sábado + domingo). */
    public int ingresadosFinSemana() {
        String[] r = Fechas.finDeSemana();
        return countRango(r[0], r[1]);
    }

    // ---- Tendencias: ganados por periodo (usa la fecha de registro) ----

    /** Ganados por semana (año-semana ISO), de la más reciente a la más antigua. */
    public List<Object[]> ganadosPorSemana() {
        return rows("SELECT " + Db.yWeekKey("creado") + " periodo, COUNT(*) n "
                  + "FROM persona WHERE creado IS NOT NULL GROUP BY periodo ORDER BY periodo");
    }

    /** Ganados por mes (año-mes). */
    public List<Object[]> ganadosPorMes() {
        return rows("SELECT " + Db.ym("creado") + " periodo, COUNT(*) n "
                  + "FROM persona WHERE creado IS NOT NULL GROUP BY periodo ORDER BY periodo");
    }

    /** Ganados por año. */
    public List<Object[]> ganadosPorAnio() {
        return rows("SELECT " + Db.yYear("creado") + " periodo, COUNT(*) n "
                  + "FROM persona WHERE creado IS NOT NULL GROUP BY periodo ORDER BY periodo");
    }

    private int countRango(String desde, String hastaExcl) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM persona WHERE eliminado=0 AND creado IS NOT NULL AND creado >= ? AND creado < ?")) {
            ps.setString(1, desde);
            ps.setString(2, hastaExcl);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private int count(String sql) {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Inscritos por curso: [id, nombre, cantidad] (para enlazar a la lista de personas). */
    public List<Object[]> inscritosPorCurso() {
        return rows("SELECT c.id, c.nombre, COUNT(p.id) FROM curso c "
                  + "LEFT JOIN persona_curso pc ON pc.curso_id=c.id "
                  + "LEFT JOIN persona p ON p.id=pc.persona_id AND p.eliminado=0 "
                  + "GROUP BY c.id ORDER BY 3 DESC, c.nombre");
    }
    public List<Object[]> cursosPorPersona() {
        return rows("SELECT p.nombres_completos, COALESCE(g.nombre,''), COUNT(pc.curso_id) FROM persona p "
                  + "LEFT JOIN grupo g ON g.id=p.grupo_id LEFT JOIN persona_curso pc ON pc.persona_id=p.id "
                  + "WHERE p.eliminado=0 GROUP BY p.id, g.nombre ORDER BY 3 DESC, p.nombres_completos");
    }
    public List<Object[]> personasPorGrupo() {
        return rows("SELECT g.nombre, COALESCE(g.lider,''), COUNT(p.id) FROM grupo g "
                  + "LEFT JOIN persona p ON p.grupo_id=g.id AND p.eliminado=0 GROUP BY g.id ORDER BY 3 DESC, g.nombre");
    }

    private List<Object[]> rows(String sql) {
        List<Object[]> out = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                out.add(row);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }
}
