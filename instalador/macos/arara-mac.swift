// arara-mac: o que o Java não alcança no macOS. Vai em Arara.app/Contents/MacOS e é chamado pelo MacHelper.java.
//   arara-mac audio   grava o áudio do sistema (ScreenCaptureKit, macOS 13+) e escreve PCM 16 kHz mono 16-bit
//                     little-endian na saída padrão até ser encerrado
//   arara-mac agenda  escreve em JSON os eventos do calendário em volta de agora (EventKit), com os convidados
// Sai com 2 quando falta a permissão do macOS e com 3 em qualquer outro erro.
// shortcut: o ScreenCaptureKit exige a permissão "Gravação de Tela e Áudio do Sistema", mesmo só para áudio. Os taps
// do Core Audio (macOS 14.2+) pedem só áudio do sistema; trocar se a permissão de tela incomodar.
import AVFoundation
import CoreGraphics
import EventKit
import Foundation
import ScreenCaptureKit

func sair(_ codigo: Int32, _ mensagem: String) -> Never {
    FileHandle.standardError.write((mensagem + "\n").data(using: .utf8)!)
    exit(codigo)
}

final class Captura: NSObject, SCStreamOutput, SCStreamDelegate {
    private let saida = FileHandle.standardOutput
    private let alvo = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true)!
    private var conversor: AVAudioConverter?
    private var stream: SCStream?

    func iniciar() async throws {
        let conteudo = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard let tela = conteudo.displays.first else { sair(3, "nenhuma tela para gravar") }
        let filtro = SCContentFilter(display: tela, excludingApplications: [], exceptingWindows: [])
        let config = SCStreamConfiguration()
        config.capturesAudio = true
        config.excludesCurrentProcessAudio = true
        config.sampleRate = 16000
        config.channelCount = 1
        // O vídeo não interessa: o menor quadro possível, um por segundo.
        config.width = 2
        config.height = 2
        config.minimumFrameInterval = CMTime(value: 1, timescale: 1)
        let s = SCStream(filter: filtro, configuration: config, delegate: self)
        try s.addStreamOutput(self, type: .audio, sampleHandlerQueue: DispatchQueue(label: "br.arara.audio"))
        // Sem uma saída de vídeo o ScreenCaptureKit reclama no log a cada quadro.
        try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: DispatchQueue(label: "br.arara.tela"))
        try await s.startCapture()
        stream = s
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer amostra: CMSampleBuffer, of tipo: SCStreamOutputType) {
        guard tipo == .audio, amostra.isValid, amostra.numSamples > 0 else { return }
        try? amostra.withAudioBufferList { lista, _ in
            // O sistema nem sempre respeita os 16 kHz mono pedidos: converte a partir do formato que chegou.
            guard let descricao = amostra.formatDescription?.audioStreamBasicDescription,
                  let formato = AVAudioFormat(standardFormatWithSampleRate: descricao.mSampleRate,
                                              channels: descricao.mChannelsPerFrame),
                  let entrada = AVAudioPCMBuffer(pcmFormat: formato, bufferListNoCopy: lista.unsafePointer)
            else { return }
            if conversor == nil || conversor!.inputFormat != formato { conversor = AVAudioConverter(from: formato, to: alvo) }
            let capacidade = AVAudioFrameCount(Double(entrada.frameLength) * 16000 / formato.sampleRate) + 64
            guard let conv = conversor, let convertido = AVAudioPCMBuffer(pcmFormat: alvo, frameCapacity: capacidade) else { return }
            var entregue = false
            var erro: NSError?
            conv.convert(to: convertido, error: &erro) { _, estado in
                if entregue {
                    estado.pointee = .noDataNow
                    return nil
                }
                entregue = true
                estado.pointee = .haveData
                return entrada
            }
            guard erro == nil, convertido.frameLength > 0, let dados = convertido.int16ChannelData else { return }
            // Se o Arara fechou, a escrita falha (SIGPIPE) e este processo acaba junto.
            saida.write(Data(bytes: dados[0], count: Int(convertido.frameLength) * 2))
        }
    }

    func stream(_ stream: SCStream, didStopWithError erro: Error) {
        sair(3, "a gravação parou: \(erro.localizedDescription)")
    }
}

func audio() -> Never {
    if !CGPreflightScreenCaptureAccess() {
        _ = CGRequestScreenCaptureAccess() // mostra o pedido do macOS; só vale depois de reabrir o app
        sair(2, "sem permissão de Gravação de Tela e Áudio do Sistema")
    }
    let captura = Captura()
    Task {
        do {
            try await captura.iniciar()
        } catch {
            sair(3, "não consegui gravar o áudio do sistema: \(error.localizedDescription)")
        }
    }
    dispatchMain()
}

final class Resposta: @unchecked Sendable {
    var permitido = false
}

func agenda() -> Never {
    let agenda = EKEventStore()
    let resposta = Resposta()
    let espera = DispatchSemaphore(value: 0)
    if #available(macOS 14.0, *) {
        agenda.requestFullAccessToEvents { ok, _ in
            resposta.permitido = ok
            espera.signal()
        }
    } else {
        agenda.requestAccess(to: .event) { ok, _ in
            resposta.permitido = ok
            espera.signal()
        }
    }
    espera.wait()
    if !resposta.permitido { sair(2, "sem permissão para ler o calendário") }

    // Do que começou há pouco (ou está em andamento) até o que começa em 15 min.
    let agora = Date()
    let busca = agenda.predicateForEvents(withStart: agora.addingTimeInterval(-600), end: agora.addingTimeInterval(900),
                                          calendars: nil)
    var eventos: [[String: Any]] = []
    for evento in agenda.events(matching: busca) where !evento.isAllDay {
        var pessoas: [[String: Any]] = []
        for pessoa in evento.attendees ?? [] {
            let email = pessoa.url.absoluteString.replacingOccurrences(of: "mailto:", with: "")
            pessoas.append(["nome": pessoa.name ?? email, "eu": pessoa.isCurrentUser])
        }
        eventos.append([
            "titulo": evento.title ?? "",
            "inicio": evento.startDate.timeIntervalSince1970,
            "fim": evento.endDate.timeIntervalSince1970,
            "participantes": pessoas,
        ])
    }
    guard let json = try? JSONSerialization.data(withJSONObject: eventos) else { sair(3, "não consegui montar o JSON") }
    FileHandle.standardOutput.write(json)
    exit(0)
}

switch CommandLine.arguments.dropFirst().first {
case "audio": audio()
case "agenda": agenda()
default: sair(3, "uso: arara-mac audio | agenda")
}
