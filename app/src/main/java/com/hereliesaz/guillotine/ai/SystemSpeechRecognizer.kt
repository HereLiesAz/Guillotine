package com.hereliesaz.guillotine.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One-shot fallback for command dictation when Guillotine's bundled/local ASR cannot run.
 *
 * This delegates to Android's installed speech-recognition service. Depending on the service and
 * device settings, recognition may be local or may use the network; callers must label it as
 * "system speech recognition" rather than claiming it is offline.
 */
object SystemSpeechRecognizer {

    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    suspend fun recognizeOnce(context: Context): String? = withContext(Dispatchers.Main.immediate) {
        if (!isAvailable(context)) return@withContext null

        suspendCancellableCoroutine { cont ->
            val recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            var finished = false

            fun finish(text: String?) {
                if (finished) return
                finished = true
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
                if (cont.isActive) cont.resume(text?.trim()?.ifBlank { null })
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onError(error: Int) {
                    finish(null)
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    finish(text)
                }
            })

            cont.invokeOnCancellation {
                if (!finished) {
                    finished = true
                    runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your Guillotine command")
            }
            try {
                recognizer.startListening(intent)
            } catch (_: Throwable) {
                finish(null)
            }
        }
    }
}
