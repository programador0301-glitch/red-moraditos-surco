package com.redsurco.web.dao;

import com.redsurco.web.model.Meta;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Almacén agregado de Metas: totales por grupo y periodo (se cargan desde archivo o a mano). */
public class MetasDao {
    private final Connection con;
    public MetasDao(Connection con) { this.con = con; }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Periodos distintos que tienen metas cargadas, ordenados. */
    public List<String> periodos() {
        List<String> out = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT periodo FROM meta ORDER BY periodo")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Meta> porPeriodo(String periodo) {
        List<Meta> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM meta WHERE periodo=? ORDER BY codigo")) {
            ps.setString(1, periodo);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(leer(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Meta> todas() {
        List<Meta> out = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM meta ORDER BY periodo, codigo")) {
            while (rs.next()) out.add(leer(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public Meta get(String periodo, String codigo) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM meta WHERE periodo=? AND codigo=?")) {
            ps.setString(1, periodo); ps.setString(2, codigo);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? leer(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Inserta o actualiza los totales de un grupo en un periodo (clave periodo+codigo). */
    public void upsert(Meta m) {
        String sql = "INSERT INTO meta(periodo,codigo,nombre,ins_lider,ins_disc,res_lider,res_disc) "
                   + "VALUES(?,?,?,?,?,?,?) "
                   + "ON CONFLICT(periodo,codigo) DO UPDATE SET "
                   + "nombre=excluded.nombre, ins_lider=excluded.ins_lider, ins_disc=excluded.ins_disc, "
                   + "res_lider=excluded.res_lider, res_disc=excluded.res_disc";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, m.periodo);
            ps.setString(2, m.codigo);
            ps.setString(3, nz(m.nombre));
            ps.setInt(4, m.insLider);
            ps.setInt(5, m.insDisc);
            ps.setInt(6, m.resLider);
            ps.setInt(7, m.resDisc);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void delete(String periodo, String codigo) {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM meta WHERE periodo=? AND codigo=?")) {
            ps.setString(1, periodo); ps.setString(2, codigo); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private Meta leer(ResultSet rs) throws SQLException {
        Meta m = new Meta();
        m.id = rs.getInt("id");
        m.periodo = rs.getString("periodo");
        m.codigo = rs.getString("codigo");
        m.nombre = nz(rs.getString("nombre"));
        m.insLider = rs.getInt("ins_lider");
        m.insDisc = rs.getInt("ins_disc");
        m.resLider = rs.getInt("res_lider");
        m.resDisc = rs.getInt("res_disc");
        return m;
    }
}
