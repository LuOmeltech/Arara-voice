package br.arara.core;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * O que o Java não alcança no macOS, feito pelo executável {@code arara-mac} (instalador/macos/arara-mac.swift), que
 * vai dentro do Arara.app: gravar o áudio do sistema (ScreenCaptureKit) e ler o calendário (EventKit).
 */
final class MacHelper {
    private MacHelper() {}

    /** Código de saída do arara-mac quando falta a permissão do macOS. */
    static final int NO_PERMISSION = 2;

    /** {@code -Darara.helper=<arquivo>} ou Arara.app/Contents/MacOS/arara-mac (o java.home é Contents/runtime/Contents/Home). */
    static Path path() {
        String custom = System.getProperty("arara.helper");
        if (custom != null) return Path.of(custom);
        return Path.of(System.getProperty("java.home"), "..", "..", "..", "MacOS", "arara-mac").normalize();
    }

    /** O evento do calendário que está acontecendo agora (ou o mais perto de começar), ou null. Bloqueia. */
    static CalendarEvent agenda(Path helper) {
        try {
            Process p = new ProcessBuilder(helper.toString(), "agenda").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String json = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // O pedido de permissão do calendário fica na tela até a pessoa responder.
            if (!p.waitFor(2, TimeUnit.MINUTES) || p.exitValue() != 0) return null;
            return pick(json, System.currentTimeMillis() / 1000.0);
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Escolhe entre os eventos em volta de agora: o que tem convidados e está em andamento; senão o que começa mais
     * perto. Formato: [{"titulo":"...","inicio":1763470800,"fim":1763474400,"participantes":[{"nome":"Ana","eu":false}]}]
     */
    static CalendarEvent pick(String json, double now) {
        Map<?, ?> best = null;
        double bestScore = Double.MAX_VALUE;
        for (Object o : (List<?>) Json.parse(json.strip())) {
            Map<?, ?> e = (Map<?, ?>) o;
            double start = ((Number) e.get("inicio")).doubleValue(), end = ((Number) e.get("fim")).doubleValue();
            double score = start <= now && now < end ? 0 : Math.abs(start - now);
            if (((List<?>) e.get("participantes")).isEmpty()) score += 3600; // bloco pessoal na agenda perde para a reunião
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best == null) return null;
        List<CalendarEvent.Person> people = new ArrayList<>();
        for (Object o : (List<?>) best.get("participantes")) {
            Map<?, ?> p = (Map<?, ?>) o;
            people.add(new CalendarEvent.Person((String) p.get("nome"), Boolean.TRUE.equals(p.get("eu"))));
        }
        return new CalendarEvent((String) best.get("titulo"), people);
    }

    /** Áudio do sistema: o arara-mac escreve PCM 16 kHz, mono, 16-bit little-endian na saída padrão. */
    static final class SystemAudio implements DefaultDictationEngine.AudioSource {
        private static final int CHUNK = 512;
        private final Path helper;
        private volatile Process process;
        /** Avisado quando o arara-mac para sozinho, com a mensagem para o usuário. */
        volatile Consumer<String> onFail = m -> {};

        SystemAudio(Path helper) {
            this.helper = helper;
        }

        @Override
        public void start(Consumer<short[]> onChunk) throws IOException {
            if (!Files.isExecutable(helper)) throw new IOException("arara-mac não encontrado em " + helper);
            Process p = new ProcessBuilder(helper.toString(), "audio").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process = p;
            Thread t = new Thread(() -> {
                byte[] buf = new byte[CHUNK * 2];
                try (DataInputStream in = new DataInputStream(p.getInputStream())) {
                    for (;;) {
                        in.readFully(buf);
                        short[] samples = new short[CHUNK];
                        for (int i = 0; i < CHUNK; i++) samples[i] = (short) ((buf[2 * i] & 0xff) | (buf[2 * i + 1] << 8));
                        onChunk.accept(samples);
                    }
                } catch (EOFException e) {
                    // o arara-mac saiu: abaixo decide se foi o stop() ou um erro
                } catch (IOException e) {
                    // idem
                }
                if (process != p) return; // stop()
                int code = -1;
                try {
                    if (p.waitFor(2, TimeUnit.SECONDS)) code = p.exitValue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onFail.accept(code == NO_PERMISSION
                        ? "Sem permissão para gravar o áudio do sistema. Ligue o Arara em Ajustes › Privacidade e Segurança › Gravação de Tela e Áudio do Sistema e reabra o app."
                        : "A gravação do áudio do sistema parou.");
            }, "arara-audio-sistema");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void stop() {
            Process p = process;
            process = null;
            if (p != null) p.destroy();
        }
    }
}
