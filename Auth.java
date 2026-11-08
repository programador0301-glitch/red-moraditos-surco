package com.redsurco.web;

import com.redsurco.web.model.Usuario;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autenticación con roles (ADMIN / USUARIO): contraseñas con hash PBKDF2 y
 * sesiones en memoria (cookie SID). Todo con el JDK, sin librerías externas.
 */
public final class Auth {

    /** Lo que se guarda por sesión: usuario y su rol. */
    private record Sesion(String username, String rol) { }

    private static final SecureRandom RNG = new SecureRandom();
    private static final ConcurrentHashMap<String, Sesion> SESIONES = new ConcurrentHashMap<>();

    private Auth() { }

    /** Crea el usuario admin/admin123 (rol ADMIN) si no hay ningún usuario. */
    public static void crearAdminSiHaceFalta() {
        try (Connection con = Db.open()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM usuario");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) return;
            }
            crearUsuario(con, "admin", "admin123", Usuario.ADMIN);
            System.out.println(">> Usuario inicial: admin / admin123 (Administrador). Cámbialo luego.");
        } catch (Exception e) {
            throw new RuntimeException("No se pudo crear el usuario inicial: " + e.getMessage(), e);
        }
    }

    // ---- autenticación / sesiones ----

    /** Devuelve el rol si usuario y contraseña son correctos; null si no. */
    public static String autenticar(String username, String password) {
        try (Connection con = Db.open();
             PreparedStatement ps = con.prepareStatement("SELECT salt, hash, rol FROM usuario WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                byte[] salt = Base64.getDecoder().decode(rs.getString("salt"));
                String ok = rs.getString("hash");
                return constantesIguales(ok, pbkdf2(password, salt)) ? rs.getString("rol") : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static String crearSesion(String username, String rol) {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        SESIONES.put(token, new Sesion(username, rol));
        return token;
    }

    public static String usuarioDeSesion(String token) {
        Sesion s = token == null ? null : SESIONES.get(token);
        return s == null ? null : s.username();
    }

    public static String rolDeSesion(String token) {
        Sesion s = token == null ? null : SESIONES.get(token);
        return s == null ? null : s.rol();
    }

    public static void cerrarSesion(String token) { if (token != null) SESIONES.remove(token); }

    /** Tras editar/eliminar un usuario, actualiza o cierra sus sesiones activas. */
    private static void sincronizarSesiones(String usernameAntes, String usernameDespues, String rolDespues) {
        SESIONES.replaceAll((tok, s) ->
                s.username().equals(usernameAntes) ? new Sesion(usernameDespues, rolDespues) : s);
    }

    // ---- gestión de usuarios ----

    public static List<Usuario> listar(Connection con) throws Exception {
        List<Usuario> out = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, username, rol, grupo FROM usuario ORDER BY username")) {
            while (rs.next()) out.add(new Usuario(rs.getInt("id"), rs.getString("username"), rs.getString("rol"), rs.getString("grupo")));
        }
        return out;
    }

    public static Usuario buscar(Connection con, int id) throws Exception {
        try (PreparedStatement ps = con.prepareStatement("SELECT id, username, rol, grupo FROM usuario WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Usuario(rs.getInt("id"), rs.getString("username"), rs.getString("rol"), rs.getString("grupo")) : null;
            }
        }
    }

    /** Código de grupo propio de un usuario (por su username). "" si no tiene. */
    public static String grupoDeUsuario(Connection con, String username) throws Exception {
        try (PreparedStatement ps = con.prepareStatement("SELECT grupo FROM usuario WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString("grupo") != null ? rs.getString("grupo") : "";
            }
        }
    }

    public static void crearUsuario(Connection con, String username, String password, String rol) throws Exception {
        crearUsuario(con, username, password, rol, "");
    }

    public static void crearUsuario(Connection con, String username, String password, String rol, String grupo) throws Exception {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO usuario(username, salt, hash, rol, grupo) VALUES(?,?,?,?,?)")) {
            ps.setString(1, username);
            ps.setString(2, Base64.getEncoder().encodeToString(salt));
            ps.setString(3, pbkdf2(password, salt));
            ps.setString(4, normRol(rol));
            ps.setString(5, grupo == null ? "" : grupo.trim());
            ps.executeUpdate();
        }
    }

    public static void actualizar(Connection con, int id, String username, String rol) throws Exception {
        actualizar(con, id, username, rol, null);
    }

    /** Cambia username, rol y (si no es null) el código de grupo de un usuario. */
    public static void actualizar(Connection con, int id, String username, String rol, String grupo) throws Exception {
        String antes = null;
        Usuario u = buscar(con, id);
        if (u != null) antes = u.username;
        if (grupo != null) {
            try (PreparedStatement pg = con.prepareStatement("UPDATE usuario SET grupo=? WHERE id=?")) {
                pg.setString(1, grupo.trim()); pg.setInt(2, id); pg.executeUpdate();
            }
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE usuario SET username=?, rol=? WHERE id=?")) {
            ps.setString(1, username);
            ps.setString(2, normRol(rol));
            ps.setInt(3, id);
            ps.executeUpdate();
        }
        if (antes != null) sincronizarSesiones(antes, username, normRol(rol));
    }

    public static void resetPassword(Connection con, int id, String nuevaClave) throws Exception {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        try (PreparedStatement ps = con.prepareStatement("UPDATE usuario SET salt=?, hash=? WHERE id=?")) {
            ps.setString(1, Base64.getEncoder().encodeToString(salt));
            ps.setString(2, pbkdf2(nuevaClave, salt));
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }

    public static void eliminar(Connection con, int id) throws Exception {
        Usuario u = buscar(con, id);
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM usuario WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
        if (u != null) SESIONES.entrySet().removeIf(e -> e.getValue().username().equals(u.username));
    }

    public static int contarAdmins(Connection con) throws Exception {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM usuario WHERE rol='ADMIN'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ---- utilidades ----

    private static String normRol(String rol) {
        if (Usuario.ADMIN.equals(rol)) return Usuario.ADMIN;
        if (Usuario.REGISTRO.equals(rol)) return Usuario.REGISTRO;
        return Usuario.USUARIO;
    }

    private static String pbkdf2(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return Base64.getEncoder().encodeToString(f.generateSecret(spec).getEncoded());
    }

    private static boolean constantesIguales(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
