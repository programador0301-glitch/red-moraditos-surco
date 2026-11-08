package com.redsurco.web.dao;

import com.redsurco.web.model.Curso;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class CursoDao {
    private final Connection con;
    public CursoDao(Connection con) { this.con = con; }

    public List<Curso> findAll() {
        List<Curso> out = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,nombre,modalidad FROM curso ORDER BY nombre")) {
            while (rs.next()) out.add(new Curso(rs.getInt("id"), rs.getString("nombre"), rs.getString("modalidad")));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public Curso findById(int id) {
        try (PreparedStatement ps = con.prepareStatement("SELECT id,nombre,modalidad FROM curso WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Curso(rs.getInt("id"), rs.getString("nombre"), rs.getString("modalidad")) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /**
     * Busca un curso por nombre (tolerante a espacios, mayúsculas y acentos);
     * si no existe lo crea con el nombre limpio. Devuelve su id.
     */
    public int findOrCreate(String nombre) {
        if (nombre == null || nombre.isBlank()) return 0;
        String clave = com.redsurco.web.Txt.clave(nombre);
        for (Curso c : findAll()) {
            if (com.redsurco.web.Txt.clave(c.nombre).equals(clave)) return c.id;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO curso(nombre,modalidad) VALUES(?,'')", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, com.redsurco.web.Txt.limpia(nombre));
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getInt(1) : 0; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void insert(Curso c) {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO curso(nombre,modalidad) VALUES(?,?)")) {
            ps.setString(1, c.nombre); ps.setString(2, c.modalidad); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void update(Curso c) {
        try (PreparedStatement ps = con.prepareStatement("UPDATE curso SET nombre=?,modalidad=? WHERE id=?")) {
            ps.setString(1, c.nombre); ps.setString(2, c.modalidad); ps.setInt(3, c.id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void delete(int id) {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM curso WHERE id=?")) {
            ps.setInt(1, id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
