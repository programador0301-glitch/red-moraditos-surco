package com.redsurco.web.model;

public class Curso {
    public int id;
    public String nombre;
    public String modalidad;

    public Curso() { }
    public Curso(int id, String nombre, String modalidad) {
        this.id = id; this.nombre = nombre; this.modalidad = modalidad;
    }
    public String etiqueta() {
        return (modalidad != null && !modalidad.isBlank()) ? nombre + " (" + modalidad + ")" : nombre;
    }
}
