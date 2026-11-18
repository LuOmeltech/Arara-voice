package br.arara.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Grava uma reunião em dois canais, o microfone ({@link #EU}) e o áudio do sistema ({@link #OUTROS}), e transcreve
 * cada fala assim que ela termina. Diferente do ditado: não guarda o áudio (só o trecho ainda não transcrito), não
 * tem limite de tempo nem de silêncio, não cola nada e salva cada fala em disco na hora.
 * Os nomes vêm do calendário: o dono do calendário é o microfone e, numa reunião a dois, a outra pessoa é o áudio do
 * sistema. Com mais gente o canal do sistema fica sem nome até o usuário escolher.
 * shortcut: sem cancelamento de eco. Sem fone, o microfone ouve a caixa de som e a fala dos outros sai repetida em
 * "Você"; a saída é comparar os dois canais antes de transcrever o microfone.
 */
public final class MeetingEngine implements AutoCloseable {
    public static final String EU = "eu";
    public static final String OUTROS = "outros";

    static final int TURN = Vad.RATE; // 1 s de silêncio: a pessoa terminou de falar
    static final int MAX_PART = Vad.RATE * 25; // fala corrida: corta no ponto mais baixo dos últimos 2 s (o Whisper vê 30 s)
    private static final int EDGE = Vad.RATE;
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private final MeetingStore store;
    private final DictionaryStore dictionary;
    private final Transcriber transcriber;
    private final Map<String, DefaultDictationEngine.AudioSource> sources;
    private final Supplier<CalendarEvent> calendar; // pode demorar (pedido de permissão) e devolver null
    private final LongSupplier nanos;
    private final List<MeetingListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arara-reuniao");
        t.setDaemon(true);
        return t;
    });

    private Meeting meeting; // null: parado
    private List<Channel> channels = List.of();
    private long startNanos;
    private int pending;

    /** Um canal de áudio: guarda só o trecho que ainda não foi para o Whisper. */
    private final class Channel {
        final String key;
        final DefaultDictationEngine.AudioSource source;
        final Vad vad = new Vad();
        short[] buf = new short[Vad.RATE * 10];
        int size;
        long base; // amostra (desde o início do canal) de buf[0]
        long speech = -1; // primeira amostra com fala do trecho em andamento, ou -1
        long speechNanos; // relógio nessa amostra
        String context = ""; // fim do que este canal já disse; só a thread de transcrição mexe

        Channel(String key, DefaultDictationEngine.AudioSource source) {
            this.key = key;
            this.source = source;
        }
    }

    MeetingEngine(MeetingStore store, DictionaryStore dictionary, Transcriber transcriber,
            Map<String, DefaultDictationEngine.AudioSource> sources, Supplier<CalendarEvent> calendar, LongSupplier nanos) {
        this.store = store;
        this.dictionary = dictionary;
        this.transcriber = transcriber;
        this.sources = new LinkedHashMap<>(sources);
        this.calendar = calendar;
        this.nanos = nanos;
    }

    public MeetingStore store() {
        return store;
    }

    public void addListener(MeetingListener listener) {
        listeners.add(listener);
    }

    public synchronized boolean recording() {
        return meeting != null;
    }

    /** A reunião em gravação, ou null. */
    public synchronized Meeting current() {
        return meeting;
    }

    /** Começa a gravar. Já gravando: não faz nada. Um canal que não abre vira {@link MeetingListener#onError}; sem nenhum, não começa. */
    public synchronized void start() {
        if (meeting != null) return;
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Meeting m = new Meeting(ID.format(now), now, "Reunião", List.of(), Map.of());
        List<Channel> open = new ArrayList<>();
        startNanos = nanos.getAsLong();
        meeting = m; // antes de abrir os canais: os blocos esperam o lock e já acham a reunião
        for (Map.Entry<String, DefaultDictationEngine.AudioSource> e : sources.entrySet()) {
            Channel c = new Channel(e.getKey(), e.getValue());
            try {
                c.source.start(chunk -> onChunk(m, c, chunk));
                open.add(c);
            } catch (Exception ex) {
                error(EU.equals(c.key) ? "Não consegui abrir o microfone." : "Não consegui gravar o áudio do sistema.");
            }
        }
        if (open.isEmpty()) {
            meeting = null;
            return;
        }
        channels = open;
        store.save(m);
        for (MeetingListener l : listeners) l.onMeeting(m);
        notifyState();
        Thread t = new Thread(() -> fromCalendar(m.id()), "arara-agenda");
        t.setDaemon(true);
        t.start();
    }

    /** O canal parou sozinho (ex.: sem permissão para gravar o áudio do sistema). A reunião segue com o outro. */
    void sourceFailed(String message) {
        error(message);
    }

    private void fromCalendar(String id) {
        CalendarEvent ev;
        try {
            ev = calendar.get();
        } catch (RuntimeException e) {
            ev = null;
        }
        if (ev == null) return;
        Meeting named;
        synchronized (this) {
            Meeting m = meeting;
            if (m == null || !m.id().equals(id)) return; // a reunião já acabou: fica como está
            named = named(m, ev);
            meeting = named;
        }
        store.save(named);
        for (MeetingListener l : listeners) l.onMeeting(named);
    }

    /** Título e participantes do evento; o dono do calendário é o microfone e, a dois, a outra pessoa é o sistema. */
    static Meeting named(Meeting m, CalendarEvent ev) {
        List<String> people = new ArrayList<>();
        List<String> others = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>(m.nomes());
        for (CalendarEvent.Person p : ev.pessoas()) {
            if (p.nome() == null || p.nome().isBlank()) continue;
            people.add(p.nome());
            if (p.eu()) names.putIfAbsent(EU, p.nome());
            else others.add(p.nome());
        }
        if (others.size() == 1) names.putIfAbsent(OUTROS, others.get(0));
        String title = ev.titulo() == null || ev.titulo().isBlank() ? m.titulo() : ev.titulo().strip();
        return new Meeting(m.id(), m.inicio(), title, people, names);
    }

    /** Troca o nome de um falante (ou o título) de uma reunião salva ou em gravação. */
    public void update(Meeting changed) {
        synchronized (this) {
            if (meeting != null && meeting.id().equals(changed.id())) meeting = changed;
        }
        store.save(changed);
        for (MeetingListener l : listeners) l.onMeeting(changed);
    }

    private synchronized void onChunk(Meeting m, Channel c, short[] chunk) {
        if (meeting == null || !meeting.id().equals(m.id())) return;
        if (c.size + chunk.length > c.buf.length) c.buf = Arrays.copyOf(c.buf, Math.max(c.buf.length * 2, c.size + chunk.length));
        System.arraycopy(chunk, 0, c.buf, c.size, chunk.length);
        c.size += chunk.length;
        c.vad.feed(chunk);
        long total = c.base + c.size, end = c.vad.speechEnd();
        if (c.speech < 0 && end > c.base) {
            c.speech = Math.max(c.base, end - (long) Vad.SPEECH_CHUNKS * chunk.length);
            c.speechNanos = nanos.getAsLong() - (total - c.speech) * 1_000_000_000L / Vad.RATE;
        }
        if (c.speech < 0) {
            if (c.size > 2 * EDGE) drop(c, c.size - EDGE); // silêncio: guarda só a folga para a próxima fala
        } else if (total - end >= TURN || (end - c.speech >= DefaultDictationEngine.MIN_PART && total - end >= DefaultDictationEngine.PAUSE)) {
            cut(c, total);
        } else if (total - c.speech >= MAX_PART) {
            cut(c, c.base + DefaultDictationEngine.quietest(c.buf, c.size - 2 * Vad.RATE, c.size));
        }
    }

    /** Manda para o Whisper a fala do trecho até {@code to} (amostra do canal) e deixa no buffer só o que vem depois. */
    private void cut(Channel c, long to) {
        long end = c.vad.speechEnd();
        int from = (int) Math.max(0, c.speech - EDGE - c.base);
        int until = (int) Math.min(to - c.base, end + EDGE - c.base);
        short[] pcm = Arrays.copyOfRange(c.buf, from, Math.max(from, until));
        int second = (int) Math.max(0, (c.speechNanos - startNanos) / 1_000_000_000L);
        drop(c, (int) (to - c.base));
        c.speech = end > to ? to : -1;
        if (c.speech >= 0) c.speechNanos = nanos.getAsLong() - (long) c.size * 1_000_000_000L / Vad.RATE;
        Meeting m = meeting;
        pending++;
        worker.execute(() -> transcribe(m.id(), c, pcm, second));
        notifyState();
    }

    private static void drop(Channel c, int n) {
        System.arraycopy(c.buf, n, c.buf, 0, c.size - n);
        c.size -= n;
        c.base += n;
    }

    private void transcribe(String id, Channel c, short[] pcm, int second) {
        try {
            String text = dictionary.apply(transcriber.transcribe(pcm, c.context).strip());
            if (!text.isEmpty() && !invented(text)) {
                String all = c.context + " " + text;
                c.context = all.substring(Math.max(0, all.length() - 300));
                MeetingLine line = new MeetingLine(second, c.key, text);
                store.append(id, line);
                Meeting m = meetingOf(id);
                if (m != null) for (MeetingListener l : listeners) l.onLine(m, line);
            }
        } catch (RuntimeException e) {
            error("Falha ao transcrever um trecho da reunião.");
        } finally {
            synchronized (this) {
                pending--;
            }
            notifyState();
        }
    }

    /** O Whisper inventa créditos de legenda em áudio sem fala (música de espera, ruído). */
    static boolean invented(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("amara.org") || t.startsWith("legendas pela comunidade");
    }

    private Meeting meetingOf(String id) {
        synchronized (this) {
            if (meeting != null && meeting.id().equals(id)) return meeting;
        }
        for (Meeting m : store.all()) if (m.id().equals(id)) return m; // já parou: as últimas falas ainda chegam
        return null;
    }

    /** Para de gravar; o que já foi dito e ainda não voltou do Whisper continua chegando ({@code pending} até 0). */
    public synchronized void stop() {
        if (meeting == null) return;
        for (Channel c : channels) {
            c.source.stop();
            if (c.speech >= 0) cut(c, c.base + c.size);
        }
        channels = List.of();
        meeting = null;
        notifyState();
    }

    private void notifyState() {
        boolean rec;
        int p;
        synchronized (this) {
            rec = meeting != null;
            p = pending;
        }
        for (MeetingListener l : listeners) l.onState(rec, p);
    }

    private void error(String message) {
        for (MeetingListener l : listeners) l.onError(message);
    }

    @Override
    public void close() {
        stop();
        worker.shutdown();
    }
}
