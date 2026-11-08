package com.redsurco.web;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Utilidades de fechas compartidas (para que tarjetas y listas usen el mismo rango). */
public final class Fechas {
    private Fechas() {}

    /**
     * Rango del fin de semana más reciente (sábado + domingo) como texto para comparar
     * contra la columna 'creado' (formato 'yyyy-MM-dd HH:mm:ss').
     * Devuelve [desdeInclusive, hastaExclusive].
     */
    public static String[] finDeSemana() {
        LocalDate hoy = LocalDate.now();
        int diasDesdeSab = (hoy.getDayOfWeek().getValue() - DayOfWeek.SATURDAY.getValue() + 7) % 7;
        LocalDate sabado = hoy.minusDays(diasDesdeSab);
        LocalDate lunesSig = sabado.plusDays(2); // sábado + domingo
        return new String[]{ sabado + " 00:00:00", lunesSig + " 00:00:00" };
    }
}
