package com.redsurco.web.model;

public class Usuario {
    public static final String ADMIN = "ADMIN";
    public static final String USUARIO = "USUARIO";
    public static final String REGISTRO = "REGISTRO";   // solo registrar personas (equipo de conectar)

    public int id;
    public String username;
    public String rol = USUARIO;
    public String grupo = "";   // código de grupo propio del usuario (para registrar en su grupo)

    public Usuario() { }
    public Usuario(int id, String username, String rol) {
        this.id = id; this.username = username; this.rol = rol;
    }
    public Usuario(int id, String username, String rol, String grupo) {
        this.id = id; this.username = username; this.rol = rol; this.grupo = grupo == null ? "" : grupo;
    }

    public boolean esAdmin() { return ADMIN.equals(rol); }
    public boolean soloRegistro() { return REGISTRO.equals(rol); }
    public String rolLabel() {
        if (ADMIN.equals(rol)) return "Administrador";
        if (REGISTRO.equals(rol)) return "Conectar";
        return "Usuario";
    }
}
