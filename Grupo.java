package com.redsurco.web.model;

public class Grupo {
    public int id;
    public String nombre;
    public String lider;

    public Grupo() { }
    public Grupo(int id, String nombre, String lider) {
        this.id = id; this.nombre = nombre; this.lider = lider;
    }
}
