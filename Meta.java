package com.redsurco.web.model;

/** Totales de un grupo en un periodo (año-trimestre). Alimenta la vista de Metas. */
public class Meta {
    public int id;
    public String periodo;      // "2026-3"
    public String codigo;       // "C181"
    public String nombre;       // nombre del líder / grupo
    public int insLider;        // inscritos: líderes
    public int insDisc;         // inscritos: asistentes (Disc)
    public int resLider;        // reset: líderes
    public int resDisc;         // reset: asistentes (Disc)

    public int insTotal() { return insLider + insDisc; }
    public int resTotal() { return resLider + resDisc; }

    /** Líder según la métrica (true = reset). */
    public int lider(boolean reset) { return reset ? resLider : insLider; }
    /** Disc según la métrica. */
    public int disc(boolean reset)  { return reset ? resDisc : insDisc; }
    /** Total según la métrica. */
    public int total(boolean reset) { return reset ? resTotal() : insTotal(); }
}
