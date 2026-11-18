package br.arara.core;

import java.util.List;

/** Evento do calendário que está acontecendo agora. */
record CalendarEvent(String titulo, List<Person> pessoas) {
    /** @param eu é o dono do calendário */
    record Person(String nome, boolean eu) {}
}
