package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeetingEngineTest {
    @TempDir
    Path dir;

    final DefaultDictationEngineTest.FakeMic mic = new DefaultDictationEngineTest.FakeMic();
    final DefaultDictationEngineTest.FakeMic system = new DefaultDictationEngineTest.FakeMic();
    final AtomicLong clock = new AtomicLong();
    final BlockingQueue<MeetingLine> lines = new LinkedBlockingQueue<>();
    final BlockingQueue<Meeting> meetings = new LinkedBlockingQueue<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    CalendarEvent event;

    MeetingEngine engine(Transcriber t) {
        Map<String, DefaultDictationEngine.AudioSource> sources = new LinkedHashMap<>();
        sources.put(MeetingEngine.EU, mic);
        sources.put(MeetingEngine.OUTROS, system);
        MeetingEngine e = new MeetingEngine(new MeetingStore(dir.resolve("reunioes")),
                new JsonDictionaryStore(dir.resolve("dicionario.json")), t, sources, () -> event, clock::get);
        e.addListener(new MeetingListener() {
            @Override
            public void onLine(Meeting m, MeetingLine line) {
                lines.add(line);
            }

            @Override
            public void onMeeting(Meeting m) {
                meetings.add(m);
            }

            @Override
            public void onError(String message) {
                errors.add(message);
            }
        });
        return e;
    }

    /** Empurra blocos e anda o relógio junto (1 bloco = 32 ms). */
    void push(DefaultDictationEngineTest.FakeMic source, short[] chunk, int times) {
        for (int i = 0; i < times; i++) {
            clock.addAndGet(VadTest.CHUNK * 1_000_000_000L / Vad.RATE);
            source.push(chunk);
        }
    }

    @Test
    void eachChannelBecomesItsOwnSpeakerInTimeOrder() throws Exception {
        MeetingEngine e = engine(pcm -> "fala de " + pcm.length);
        e.start();
        String id = e.current().id();
        push(mic, VadTest.QUIET, 100); // 3,2 s de silêncio nos dois
        push(mic, VadTest.SPEECH, 20);
        push(mic, VadTest.QUIET, 40); // 1 s de pausa: a fala fecha sem esperar o fim da reunião
        MeetingLine first = lines.poll(5, TimeUnit.SECONDS);
        assertEquals(MeetingEngine.EU, first.falante());
        assertEquals(3, first.segundo());
        assertEquals("fala de " + (20 * VadTest.CHUNK + 2 * Vad.RATE), first.texto(), "fala + 1 s de folga de cada lado");

        push(system, VadTest.QUIET, 100);
        push(system, VadTest.SPEECH, 20); // ainda falando quando a reunião para
        e.stop();
        MeetingLine second = lines.poll(5, TimeUnit.SECONDS);
        assertEquals(MeetingEngine.OUTROS, second.falante());
        assertEquals(8, second.segundo(), "o relógio é o da reunião, não o do canal");
        assertNull(mic.onChunk, "microfone desligado");
        assertNull(system.onChunk);
        assertFalse(e.recording());

        MeetingStore store = e.store();
        assertEquals(List.of(first, second), store.lines(id));
        assertEquals(id, store.all().get(0).id());
    }

    @Test
    void silenceIsNeverTranscribedAndDoesNotPileUp() throws Exception {
        MeetingEngine e = engine(pcm -> "inventado");
        e.start();
        push(mic, VadTest.QUIET, 2000); // mais de 1 min
        e.stop();
        assertNull(lines.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void longSpeechIsCutWithoutWaitingForAPause() throws Exception {
        MeetingEngine e = engine(pcm -> "trecho");
        e.start();
        // Fala com respiros curtos (64 ms), sem pausa: ruído constante faria o piso do VAD subir e virar "silêncio".
        for (int i = 0; i < (MeetingEngine.MAX_PART + Vad.RATE) / VadTest.CHUNK / 12; i++) {
            push(mic, VadTest.SPEECH, 10);
            push(mic, VadTest.QUIET, 2);
        }
        assertEquals(0, lines.poll(5, TimeUnit.SECONDS).segundo());
        e.stop();
        assertTrue(lines.poll(5, TimeUnit.SECONDS).segundo() > 20, "o resto começa onde o corte parou");
    }

    @Test
    void calendarNamesTheOwnerAndTheOnlyOtherPerson() throws Exception {
        event = new CalendarEvent("1:1 Ana e Lu", List.of(new CalendarEvent.Person("Lu", true), new CalendarEvent.Person("Ana", false)));
        MeetingEngine e = engine(pcm -> "oi");
        e.start();
        Meeting m = meetings.poll(5, TimeUnit.SECONDS);
        if (m.participantes().isEmpty()) m = meetings.poll(5, TimeUnit.SECONDS); // o primeiro aviso é o da reunião sem nome
        assertEquals("1:1 Ana e Lu", m.titulo());
        assertEquals("Lu", m.nome(MeetingEngine.EU));
        assertEquals("Ana", m.nome(MeetingEngine.OUTROS));
        e.stop();
        assertEquals("Ana", e.store().all().get(0).nome(MeetingEngine.OUTROS), "salvo em disco");
    }

    @Test
    void withMorePeopleTheSystemChannelStaysUnnamedUntilTheUserChooses() {
        Meeting m = new Meeting("x", java.time.LocalDateTime.now(), "Reunião", List.of(), Map.of());
        Meeting named = MeetingEngine.named(m, new CalendarEvent("Semanal", List.of(new CalendarEvent.Person("Lu", true),
                new CalendarEvent.Person("Ana", false), new CalendarEvent.Person("Bia", false))));
        assertEquals(List.of("Lu", "Ana", "Bia"), named.participantes());
        assertEquals("Outros", named.nome(MeetingEngine.OUTROS));
        assertTrue(named.texto(List.of(new MeetingLine(75, MeetingEngine.EU, "Bom dia."))).contains("[01:15] Lu: Bom dia."));
    }

    @Test
    void aChannelThatDoesNotOpenIsReportedAndTheOtherKeepsRecording() throws Exception {
        Map<String, DefaultDictationEngine.AudioSource> sources = new LinkedHashMap<>();
        sources.put(MeetingEngine.EU, mic);
        sources.put(MeetingEngine.OUTROS, new DefaultDictationEngineTest.FakeMic() {
            @Override
            public void start(java.util.function.Consumer<short[]> onChunk) {
                throw new IllegalStateException("sem arara-mac");
            }
        });
        MeetingEngine e = new MeetingEngine(new MeetingStore(dir.resolve("r")), new JsonDictionaryStore(dir.resolve("d.json")),
                pcm -> "oi", sources, () -> null, clock::get);
        e.addListener(new MeetingListener() {
            @Override
            public void onError(String message) {
                errors.add(message);
            }
        });
        e.start();
        assertTrue(e.recording());
        assertEquals(List.of("Não consegui gravar o áudio do sistema."), errors);
        e.close();
    }

    @Test
    void picksTheOngoingEventWithGuests() {
        String json = "[{\"titulo\":\"Foco\",\"inicio\":900,\"fim\":2000,\"participantes\":[]},"
                + "{\"titulo\":\"Depois\",\"inicio\":1500,\"fim\":2000,\"participantes\":[{\"nome\":\"Bia\",\"eu\":false}]},"
                + "{\"titulo\":\"Agora\",\"inicio\":900,\"fim\":1800,\"participantes\":[{\"nome\":\"Lu\",\"eu\":true},{\"nome\":\"Ana\",\"eu\":false}]}]";
        CalendarEvent ev = MacHelper.pick(json, 1000);
        assertEquals("Agora", ev.titulo());
        assertEquals(List.of(new CalendarEvent.Person("Lu", true), new CalendarEvent.Person("Ana", false)), ev.pessoas());
        assertNull(MacHelper.pick("[]", 1000));
    }
}
