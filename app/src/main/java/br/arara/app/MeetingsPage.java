package br.arara.app;

import br.arara.core.Meeting;
import br.arara.core.MeetingEngine;
import br.arara.core.MeetingLine;
import br.arara.core.MeetingListener;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reuniões: botão de gravar, lista à esquerda e a transcrição com o nome de quem falou à direita. */
final class MeetingsPage extends MainView.Page implements MeetingListener {
    private static final DateTimeFormatter FULL =
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy '·' HH:mm", Locale.of("pt", "BR"));

    private final MeetingEngine engine;
    private final Runnable onChange;
    private final JButton record = Ui.button("Gravar reunião", "mic", Ui.Kind.PRIMARY);
    private final JLabel status = Ui.label(" ", Theme.regular(13), Theme.TEXT_SOFT);
    private final DefaultListModel<Meeting> model = new DefaultListModel<>();
    private final JList<Meeting> list = new JList<>(model);
    private final JLabel date = Ui.label(" ", Theme.regular(13), Theme.TEXT_SOFT);
    private final JLabel heading = Ui.label(" ", Theme.bold(20), Theme.TEXT);
    private final JLabel people = Ui.label(" ", Theme.regular(13), Theme.TEXT_SOFT);
    private final JTextArea text = new JTextArea();
    private final JButton copy = Ui.button("Copiar", "copy", Ui.Kind.PRIMARY);
    private final JButton names = Ui.button("Quem é quem", "users", Ui.Kind.OUTLINE);
    private final JButton delete = Ui.iconButton("trash-2", "Apagar esta reunião");
    private int count;
    private String error; // fica na tela até a próxima gravação

    MeetingsPage(MeetingEngine engine, Runnable onChange) {
        this.engine = engine;
        this.onChange = onChange;

        record.addActionListener(e -> {
            if (engine.recording()) engine.stop();
            else {
                error = null;
                engine.start();
            }
        });
        JPanel top = new JPanel(new BorderLayout(16, 0));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(0, 0, 16, 0));
        top.add(record, BorderLayout.WEST);
        top.add(status);
        top.add(Ui.row(8, names, copy, delete), BorderLayout.EAST);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.SURFACE);
        list.setFixedCellHeight(72);
        list.setFixedCellWidth(100); // largura fixa pequena faz a lista acompanhar a largura do cartão
        list.setCellRenderer(new Cell());
        list.getAccessibleContext().setAccessibleName("Reuniões");
        list.addListSelectionListener(e -> showDetail());
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        Ui.Round listCard = Ui.card(new BorderLayout());
        listCard.setBorder(new EmptyBorder(8, 8, 8, 8));
        listCard.setPreferredSize(new Dimension(300, 0));
        listCard.add(listScroll);

        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setMargin(new java.awt.Insets(0, 0, 0, 0));
        text.setFont(Theme.regular(15));
        text.setForeground(Theme.TEXT);
        text.getAccessibleContext().setAccessibleName("Transcrição da reunião");
        JScrollPane textScroll = new JScrollPane(text);
        textScroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        textScroll.setOpaque(false);
        textScroll.getViewport().setOpaque(false);

        copy.addActionListener(e -> {
            Meeting m = list.getSelectedValue();
            if (m != null) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(m.texto(engine.store().lines(m.id()))), null);
        });
        names.addActionListener(e -> askNames());
        delete.addActionListener(e -> {
            Meeting m = list.getSelectedValue();
            if (m == null) return;
            String[] options = {"Apagar", "Cancelar"};
            int answer = JOptionPane.showOptionDialog(this, "Apagar esta reunião? Não dá para desfazer.", "Apagar reunião",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            if (answer != 0) return;
            engine.store().delete(m.id());
            onChange.run();
        });

        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setOpaque(false);
        head.add(Ui.column(date, Ui.gap(4), heading, Ui.gap(4), people));
        JSeparator line = new JSeparator();
        line.setForeground(Theme.BORDER);
        Ui.Round detail = Ui.card(new BorderLayout(0, 12));
        detail.add(Ui.column(head, Ui.gap(16), line, Ui.gap(8)), BorderLayout.NORTH);
        detail.add(textScroll);

        JPanel columns = new JPanel(new BorderLayout(16, 0));
        columns.setOpaque(false);
        columns.add(listCard, BorderLayout.WEST);
        columns.add(detail);
        add(top, BorderLayout.NORTH);
        add(columns);

        engine.addListener(onEdt(this));
        onState(engine.recording(), 0);
    }

    @Override
    String title() {
        return "Reuniões";
    }

    @Override
    String subtitle() {
        return count + (count == 1 ? " reunião salva" : " reuniões salvas") + " neste computador.";
    }

    @Override
    void refresh() {
        Meeting selected = list.getSelectedValue();
        Meeting current = engine.current();
        List<Meeting> all = engine.store().all();
        count = all.size();
        model.clear();
        int index = all.isEmpty() ? -1 : 0;
        for (int i = 0; i < all.size(); i++) {
            model.addElement(all.get(i));
            // Gravando, a seleção acompanha a reunião em andamento; senão fica onde estava.
            String keep = current != null ? current.id() : selected != null ? selected.id() : null;
            if (all.get(i).id().equals(keep)) index = i;
        }
        if (index >= 0) list.setSelectedIndex(index);
        showDetail();
    }

    private void showDetail() {
        Meeting m = list.getSelectedValue();
        copy.setEnabled(m != null);
        names.setEnabled(m != null);
        delete.setEnabled(m != null && (engine.current() == null || !engine.current().id().equals(m.id())));
        if (m == null) {
            date.setText(" ");
            heading.setText("Nenhuma reunião ainda");
            people.setText("Entre na chamada e clique em Gravar reunião. De preferência com fone, para a sua voz não se misturar com a dos outros.");
            text.setText("");
            return;
        }
        date.setText(FULL.format(m.inicio()));
        heading.setText(m.titulo());
        people.setText(m.participantes().isEmpty() ? "Sem evento no calendário neste horário" : String.join(", ", m.participantes()));
        StringBuilder b = new StringBuilder();
        for (MeetingLine l : engine.store().lines(m.id())) {
            b.append(String.format("%02d:%02d  ", l.segundo() / 60, l.segundo() % 60)).append(m.nome(l.falante())).append('\n')
                    .append(l.texto()).append("\n\n");
        }
        boolean atEnd = text.getCaretPosition() >= text.getDocument().getLength() - 1;
        text.setText(b.toString());
        text.setCaretPosition(atEnd ? text.getDocument().getLength() : 0); // gravando, acompanha o fim
    }

    /** Escolhe o nome de cada falante entre os participantes do calendário (ou digita outro). */
    private void askNames() {
        Meeting m = list.getSelectedValue();
        if (m == null) return;
        Map<String, JComboBox<String>> fields = new LinkedHashMap<>();
        JPanel form = Ui.column();
        for (String key : new String[] {MeetingEngine.EU, MeetingEngine.OUTROS}) {
            JComboBox<String> box = new JComboBox<>(m.participantes().toArray(String[]::new));
            box.setEditable(true);
            box.setSelectedItem(m.nome(key));
            String label = MeetingEngine.EU.equals(key) ? "Microfone (você)" : "Áudio do sistema (quem está na chamada)";
            box.getAccessibleContext().setAccessibleName(label);
            fields.put(key, box);
            form.add(Ui.label(label, Theme.semibold(13), Theme.TEXT));
            form.add(Ui.gap(4));
            form.add(box);
            form.add(Ui.gap(12));
        }
        String[] options = {"Salvar", "Cancelar"};
        int answer = JOptionPane.showOptionDialog(this, form, "Quem é quem", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (answer != 0) return;
        Map<String, String> chosen = new LinkedHashMap<>(m.nomes());
        fields.forEach((key, box) -> {
            String name = String.valueOf(box.getEditor().getItem()).strip();
            if (name.isEmpty()) chosen.remove(key);
            else chosen.put(key, name);
        });
        engine.update(new Meeting(m.id(), m.inicio(), m.titulo(), m.participantes(), chosen));
    }

    @Override
    public void onState(boolean recording, int pending) {
        record.setText(recording ? "Encerrar reunião" : "Gravar reunião");
        status.setForeground(error != null ? Theme.SUPPORT : Theme.TEXT_SOFT);
        status.setText(error != null ? error
                : recording ? "Gravando o microfone e o áudio do sistema. As falas aparecem conforme são transcritas."
                : pending > 0 ? "Transcrevendo o fim da reunião: " + (pending == 1 ? "falta 1 trecho." : "faltam " + pending + " trechos.")
                : " ");
        if (!recording) showDetail(); // libera o botão de apagar
    }

    @Override
    public void onLine(Meeting meeting, MeetingLine line) {
        Meeting m = list.getSelectedValue();
        if (m != null && m.id().equals(meeting.id())) showDetail();
    }

    @Override
    public void onMeeting(Meeting meeting) {
        onChange.run();
    }

    @Override
    public void onError(String message) {
        error = message;
        onState(engine.recording(), 0);
    }

    /** O motor avisa de qualquer thread; a UI só mexe na EDT. */
    private static MeetingListener onEdt(MeetingListener l) {
        return new MeetingListener() {
            public void onState(boolean recording, int pending) { SwingUtilities.invokeLater(() -> l.onState(recording, pending)); }
            public void onLine(Meeting m, MeetingLine line) { SwingUtilities.invokeLater(() -> l.onLine(m, line)); }
            public void onMeeting(Meeting m) { SwingUtilities.invokeLater(() -> l.onMeeting(m)); }
            public void onError(String message) { SwingUtilities.invokeLater(() -> l.onError(message)); }
        };
    }

    /** Um item da lista: quando e o título. */
    private static final class Cell extends Ui.Round implements ListCellRenderer<Meeting> {
        private final JLabel when = Ui.label("", Theme.semibold(12), Theme.TEXT_SOFT);
        private final JLabel title = Ui.label("", Theme.regular(14), Theme.TEXT);

        Cell() {
            super(new BorderLayout(), Theme.SURFACE, null, 22);
            setBorder(new EmptyBorder(12, 14, 12, 14));
            add(Ui.column(when, Ui.gap(6), title), BorderLayout.NORTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Meeting> l, Meeting m, int index,
                                                      boolean selected, boolean focused) {
            setBackground(selected ? Theme.alpha(Theme.PRIMARY, 40) : Theme.SURFACE);
            when.setText(HistoryPage.when(m.inicio(), LocalDateTime.now()));
            when.setForeground(selected ? Theme.ACTIVE : Theme.TEXT_SOFT);
            title.setText(m.titulo());
            getAccessibleContext().setAccessibleName(when.getText() + ": " + m.titulo());
            return this;
        }
    }
}
