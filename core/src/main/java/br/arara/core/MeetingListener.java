package br.arara.core;

/** Eventos da gravação de reunião. Podem chegar em qualquer thread: a UI deve repassar para a EDT. */
public interface MeetingListener {
    /**
     * @param recording gravando agora
     * @param pending trechos que ainda esperam o Whisper (depois de parar, a reunião só está completa com 0)
     */
    default void onState(boolean recording, int pending) {}

    /** Fala transcrita e já salva. Os dois canais chegam fora de ordem: ordene por {@link MeetingLine#segundo}. */
    default void onLine(Meeting meeting, MeetingLine line) {}

    /** Título, participantes ou nomes mudaram (o calendário respondeu). */
    default void onMeeting(Meeting meeting) {}

    /** Mensagem curta em PT-BR para mostrar ao usuário. */
    default void onError(String message) {}
}
