package com.redsurco.web.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public class Persona {
    public static final String DNI = "DNI";
    public static final String CE = "CE";
    public static final String OTRO = "OTRO";

    /** Días de plazo para conectar a una persona nueva. */
    public static final int DIAS_PLAZO = 7;

    public int id;
    public String tipoDocumento = DNI;
    public String numDocumento;
    public String nombresCompletos;
    public String celular;
    public String correo;
    public Integer grupoId;
    public boolean reset;          // asistió al encuentro "Reset"
    public boolean conectado;      // seguimiento: ya fue contactado/conectado
    public String observaciones;   // notas del seguimiento
    public String creado;          // fecha de registro (yyyy-MM-dd HH:mm:ss)

    // para mostrar
    public String grupoNombre = "";
    public String grupoLider = "";
    public int numCursos = 0;
    public String cursosTexto = "";
    public String eliminadoEn = "";
    public String eliminadoPor = "";
    public String registradoPor = "";
    public String sexo = "";   // "H", "M" o "" (sin especificar)

    public boolean esHombre() { return "H".equals(sexo); }
    public boolean esMujer()  { return "M".equals(sexo); }
    public String sexoLabel() { return "H".equals(sexo) ? "Hombre" : "M".equals(sexo) ? "Mujer" : "—"; }

    public String tipoLabel() {
        if (CE.equals(tipoDocumento)) return "Carné de extranjería";
        if (OTRO.equals(tipoDocumento)) return "Otro documento";
        return "DNI";
    }
    public String resetLabel() { return reset ? "Sí" : "No"; }

    // ---- plazo de seguimiento (7 días desde el registro) ----

    /** Fecha de registro como LocalDate, o null si no tiene. */
    private LocalDate fechaRegistro() {
        if (creado == null || creado.isBlank()) return null;
        try {
            String d = creado.length() >= 10 ? creado.substring(0, 10) : creado;
            return LocalDate.parse(d, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) { return null; }
    }

    /** true si tiene fecha de registro (se le aplica el plazo). */
    public boolean tienePlazo() { return fechaRegistro() != null; }

    /** Días que faltan para que venza el plazo (negativo si ya venció). */
    public int diasRestantes() {
        LocalDate f = fechaRegistro();
        if (f == null) return 0;
        LocalDate limite = f.plusDays(DIAS_PLAZO);
        return (int) ChronoUnit.DAYS.between(LocalDate.now(), limite);
    }

    /** true si ya pasó el plazo de 7 días y no está conectado. */
    public boolean plazoVencido() {
        return tienePlazo() && !conectado && diasRestantes() < 0;
    }

    /** true si el plazo venció: la casilla se bloquea para usuarios no administradores. */
    public boolean bloqueadoPara(boolean admin) {
        return !admin && tienePlazo() && diasRestantes() < 0;
    }

    /** Texto corto del estado de seguimiento para la tabla. */
    public String seguimientoTexto() {
        if (conectado) return "Conectado";
        if (!tienePlazo()) return "Pendiente";
        int r = diasRestantes();
        if (r < 0) return "Vencido";
        if (r == 0) return "Vence hoy";
        return "Faltan " + r + (r == 1 ? " día" : " días");
    }

    /** Clase CSS del badge de seguimiento. */
    public String seguimientoClase() {
        if (conectado) return "yes";
        if (tienePlazo() && diasRestantes() < 0) return "no";
        return "warn";
    }
}
