import AVFoundation
import Observation

/// Reads flashcards aloud with the system voice (en-GB).
///
/// Like the site, "Stop reading" pauses automatic reading until "Read aloud" is pressed again.
@MainActor
@Observable
final class Speaker {
    private(set) var paused = false
    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private var sessionConfigured = false

    /// Speaks `text` unless reading is paused.
    func speak(_ text: String) {
        guard !paused, !text.isEmpty else { return }
        configureSession()
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-GB")
        utterance.prefersAssistiveTechnologySettings = true
        synthesizer.speak(utterance)
    }

    /// "Read aloud": resumes automatic reading and reads `text` now.
    func resume(_ text: String) {
        paused = false
        speak(text)
    }

    /// "Stop reading": stops and pauses automatic reading.
    func stopReading() {
        paused = true
        stop()
    }

    /// Stops the current speech (e.g. when leaving a screen).
    func stop() {
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
    }

    private func configureSession() {
        guard !sessionConfigured else { return }
        sessionConfigured = true
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
    }
}
