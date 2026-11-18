package br.arara.core;

/**
 * Uma fala da reunião: {"s":83,"falante":"outros","texto":"..."}.
 *
 * @param segundo segundos desde o início da reunião até o começo da fala
 * @param falante chave do canal ({@link MeetingEngine#EU} ou {@link MeetingEngine#OUTROS}); o nome fica em {@link Meeting#nomes}
 */
public record MeetingLine(int segundo, String falante, String texto) {}
