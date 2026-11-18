package br.arara.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reuniões em {@code <dados>/reunioes}: {@code <id>.json} com título, participantes e nomes dos falantes, e
 * {@code <id>.jsonl} com uma fala por linha, só acrescentada (um travamento no meio não perde as falas já salvas).
 */
public final class MeetingStore {
    private final Path dir;

    MeetingStore(Path dir) {
        this.dir = dir;
    }

    /** Grava (ou regrava) os dados da reunião; as falas não mudam. */
    public synchronized void save(Meeting m) {
        StringBuilder b = new StringBuilder("{\"inicio\":").append(Json.quote(m.inicio().toString()))
                .append(",\"titulo\":").append(Json.quote(m.titulo())).append(",\"participantes\":[");
        for (int i = 0; i < m.participantes().size(); i++) b.append(i > 0 ? "," : "").append(Json.quote(m.participantes().get(i)));
        b.append("],\"nomes\":{");
        int i = 0;
        for (Map.Entry<String, String> e : m.nomes().entrySet()) {
            b.append(i++ > 0 ? "," : "").append(Json.quote(e.getKey())).append(':').append(Json.quote(e.getValue()));
        }
        b.append("}}\n");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Json.writeAtomic(dir.resolve(m.id() + ".json"), b.toString());
    }

    public synchronized void append(String id, MeetingLine line) {
        String json = "{\"s\":" + line.segundo() + ",\"falante\":" + Json.quote(line.falante())
                + ",\"texto\":" + Json.quote(line.texto()) + "}\n";
        try {
            Files.writeString(dir.resolve(id + ".jsonl"), json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Todas as reuniões, da mais nova para a mais antiga. */
    public synchronized List<Meeting> all() {
        if (!Files.isDirectory(dir)) return List.of();
        List<Meeting> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                try {
                    out.add(read(name.substring(0, name.length() - 5), Files.readString(f)));
                } catch (IOException | RuntimeException e) {
                    // arquivo corrompido: pula e mantém o resto
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(Meeting::inicio).reversed());
        return out;
    }

    private static Meeting read(String id, String json) {
        Map<?, ?> m = (Map<?, ?>) Json.parse(json.strip());
        List<String> people = new ArrayList<>();
        for (Object p : (List<?>) m.get("participantes")) people.add((String) p);
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("nomes")).entrySet()) names.put((String) e.getKey(), (String) e.getValue());
        return new Meeting(id, LocalDateTime.parse((String) m.get("inicio")), (String) m.get("titulo"), people, names);
    }

    /** As falas na ordem em que foram ditas (os dois canais chegam do Whisper fora de ordem). */
    public synchronized List<MeetingLine> lines(String id) {
        Path f = dir.resolve(id + ".jsonl");
        if (!Files.exists(f)) return List.of();
        List<MeetingLine> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f)) {
                if (line.isBlank()) continue;
                try {
                    Map<?, ?> m = (Map<?, ?>) Json.parse(line);
                    out.add(new MeetingLine(((Number) m.get("s")).intValue(), (String) m.get("falante"), (String) m.get("texto")));
                } catch (RuntimeException e) {
                    // linha cortada por um travamento: pula
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparingInt(MeetingLine::segundo)); // estável: empate fica na ordem de chegada
        return out;
    }

    public synchronized void delete(String id) {
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
            Files.deleteIfExists(dir.resolve(id + ".jsonl"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
