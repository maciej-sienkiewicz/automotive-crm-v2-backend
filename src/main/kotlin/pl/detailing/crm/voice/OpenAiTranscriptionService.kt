package pl.detailing.crm.voice

import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service

@Service
class OpenAiTranscriptionService(
    private val transcriptionModel: OpenAiAudioTranscriptionModel
) {
    fun transcribe(audioBytes: ByteArray, filename: String): String {
        val resource = object : ByteArrayResource(audioBytes) {
            override fun getFilename() = filename
        }
        val options = OpenAiAudioTranscriptionOptions.builder()
            .model("whisper-1")
            .language("pl")
            .build()
        return transcriptionModel.call(AudioTranscriptionPrompt(resource, options)).result.output
    }
}
