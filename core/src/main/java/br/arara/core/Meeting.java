package br.arara.core;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Dados de uma reunião (o arquivo {@code <id>.json}); as falas ficam à parte, em {@code <id>.jsonl}.
 *
 * @param participantes nomes vindos do calendário (pode ser vazio)
 * @param nomes nome de cada falante, por chave do canal; sem entrada, vale o padrão ("Você", "Outros")
 */
public record Meeting(String id, LocalDateTime inicio, String titulo, List<String> participantes, Map<String, String> nomes) {
    public Meeting {
        participantes = List.copyOf(participantes);
        nomes = Map.copyOf(nomes);
    }

    /** Nome para mostrar na transcrição. */
    public String nome(String falante) {
        String n = nomes.get(falante);
        if (n != null && !n.isBlank()) return n;
        return MeetingEngine.EU.equals(falante) ? "Você" : "Outros";
    }

    /** "[01:23] Ana: texto", uma fala por linha, com o título e os participantes no topo. */
    public String texto(List<MeetingLine> falas) {
        StringBuilder b = new StringBuilder(titulo).append('\n');
        if (!participantes.isEmpty()) b.append("Participantes: ").append(String.join(", ", participantes)).append('\n');
        b.append('\n');
        for (MeetingLine l : falas) {
            b.append(String.format("[%02d:%02d] ", l.segundo() / 60, l.segundo() % 60)).append(nome(l.falante())).append(": ")
                    .append(l.texto()).append('\n');
        }
        return b.toString();
    }
}
