package com.redsurco.web.dao;

import com.redsurco.web.model.Grupo;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class GrupoDao {
    private final Connection con;
    public GrupoDao(Connection con) { this.con = con; }

    public List<Grupo> findAll() {
        List<Grupo> out = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,nombre,lider FROM grupo ORDER BY nombre")) {
            while (rs.next()) out.add(new Grupo(rs.getInt("id"), rs.getString("nombre"), rs.getString("lider")));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /**
     * Busca un grupo por nombre (tolerante a espacios, mayúsculas y acentos);
     * si no existe lo crea con el nombre limpio. Devuelve su id.
     */
    public int findOrCreate(String nombre, String lider) {
        if (nombre == null || nombre.isBlank()) return 0;
        String clave = com.redsurco.web.Txt.clave(nombre);
        // Compara contra los existentes por su clave normalizada
        for (Grupo g : findAll()) {
            if (com.redsurco.web.Txt.clave(g.nombre).equals(clave)) return g.id;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO grupo(nombre,lider) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, com.redsurco.web.Txt.limpia(nombre));
            ps.setString(2, lider == null ? "" : lider.trim());
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getInt(1) : 0; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Grupo findById(int id) {
        try (PreparedStatement ps = con.prepareStatement("SELECT id,nombre,lider FROM grupo WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Grupo(rs.getInt("id"), rs.getString("nombre"), rs.getString("lider")) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void insert(Grupo g) {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO grupo(nombre,lider) VALUES(?,?)")) {
            ps.setString(1, g.nombre); ps.setString(2, g.lider); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void update(Grupo g) {
        try (PreparedStatement ps = con.prepareStatement("UPDATE grupo SET nombre=?,lider=? WHERE id=?")) {
            ps.setString(1, g.nombre); ps.setString(2, g.lider); ps.setInt(3, g.id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void delete(int id) {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM grupo WHERE id=?")) {
            ps.setInt(1, id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
