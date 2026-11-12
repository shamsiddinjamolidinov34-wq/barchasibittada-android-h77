package uz.barchasibittada

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class TtsResult(val files: List<File>, val seconds: Int, val engine: String, val note: String)

object Tts {
    fun engineName(e: String): String = when (e) {
        "microsoft" -> "Microsoft"
        "gemini" -> "Gemini"
        "openai" -> "ChatGPT"
        "xai" -> "Grok"
        else -> "Qurilma ovozi"
    }

    /** Fayl yaratish uchun dvigatel: kaliti yo'q yoki "qurilma" bo'lsa Microsoft. */
    fun resolveEngine(): String {
        return when (Settings.ttsEngine) {
            "gemini" -> if (Keys.has(Prov.GEMINI)) "gemini" else "microsoft"
            "openai" -> if (Keys.has(Prov.OPENAI)) "openai" else "microsoft"
            "xai" -> if (Keys.has(Prov.XAI)) "xai" else "microsoft"
            else -> "microsoft"
        }
    }

    private fun chunkSize(e: String): Int = when (e) {
        "microsoft" -> 1800
        "gemini" -> 3500
        "openai" -> 3500
        else -> 6000
    }

    private fun parallel(e: String): Int = if (e == "microsoft") 3 else 2

    private suspend fun oneChunk(engine: String, text: String): AudioData {
        return when (engine) {
            "gemini" -> Api.ttsGemini(Keys.get(Prov.GEMINI), text, Settings.geminiVoice)
            "openai" -> Api.ttsOpenAi(Keys.get(Prov.OPENAI), text, Settings.openaiVoice)
            "xai" -> Api.ttsXai(Keys.get(Prov.XAI), text, Settings.xaiVoice)
            else -> AudioData(
                EdgeTts.synth(text, Settings.msVoice, EdgeTts.rateFor(Settings.speechSpeed)),
                "audio/mpeg", 24000
            )
        }
    }

    private suspend fun chunkWithRetry(engine: String, text: String): AudioData {
        var last: ProviderError? = null
        for (attempt in 0 until 3) {
            try {
                return oneChunk(engine, text)
            } catch (e: ProviderError) {
                last = e
                if (e.keyBad || e.kind == "kunlik" || e.kind == "kredit") throw e
                delay(1500L * (attempt + 1))
            }
        }
        throw last ?: ProviderError("Ovoz yaratilmadi.", 0, true)
    }

    private fun otherEngine(): String {
        if (Keys.has(Prov.GEMINI)) return "gemini"
        if (Keys.has(Prov.XAI)) return "xai"
        if (Keys.has(Prov.OPENAI)) return "openai"
        return ""
    }

    /**
     * Matnni audio fayl(lar)ga aylantiradi. Bitta fayl (sukut bo'yicha) yoki bo'laklab —
     * Sozlamalardagi tanlovga ko'ra. Hajm chegarasi yo'q (Telegram chegaralari kerak emas).
     */
    suspend fun synthesize(
        rawText: String,
        baseName: String,
        forceSingle: Boolean,
        onProgress: (Int, Int) -> Unit,
        engineOverride: String = ""
    ): TtsResult {
        val engine = if (engineOverride.isNotEmpty()) engineOverride else resolveEngine()
        var text = rawText
        var note = ""
        if (engine == "microsoft") {
            val prep = Uz.ovozgaTayyor(rawText)
            text = prep.first
            if (prep.second) note = "Kirill matn lotinga o'girilib o'qildi."
        }
        val chunks = Uz.bolaklarga(text, chunkSize(engine))
        if (chunks.isEmpty()) throw ProviderError("Ovozga aylantiriladigan matn bo'sh.")
        val pcm = engine == "gemini"
        val mp3Bitrate = if (engine == "microsoft") 48000 else 128000
        val limit = if (Settings.bookParts && !forceSingle) Settings.partMinutes.coerceAtLeast(5) * 60.0 else 0.0
        val safeBase = Files.safeName(baseName) + "-" + System.currentTimeMillis()
        val pm = PartManager(Store.audioDir(), safeBase, pcm, 24000, mp3Bitrate, limit)
        var done = 0
        var failure: ProviderError? = null
        try {
            val par = parallel(engine)
            var i = 0
            while (i < chunks.size) {
                val batch = chunks.subList(i, minOf(i + par, chunks.size))
                val results = coroutineScope {
                    batch.map { c -> async { chunkWithRetry(engine, c) } }.awaitAll()
                }
                for (r in results) {
                    pm.add(r)
                    done += 1
                }
                onProgress(done, chunks.size)
                i += par
            }
        } catch (e: CancellationException) {
            val fs = pm.finish()
            if (done == 0) for (f in fs) f.delete()
            throw e
        } catch (e: ProviderError) {
            failure = e
        } catch (e: Exception) {
            failure = ProviderError("Ovoz yaratishda xatolik: " + Safe.mask(e.message ?: "").take(120))
        }
        val files = pm.finish()
        if (failure != null) {
            if (done == 0) {
                for (f in files) f.delete()
                val alt = otherEngine()
                if (engine == "microsoft" && alt.isNotEmpty() && engineOverride.isEmpty()) {
                    val r = synthesize(rawText, baseName, forceSingle, onProgress, alt)
                    return TtsResult(r.files, r.seconds, r.engine, "Microsoft ovozi ishlamadi, " + engineName(alt) + " ishlatildi.")
                }
                throw failure
            }
            note = "Ovoz qisman tayyor bo'ldi (" + done + "/" + chunks.size + "): " + failure.msg
        }
        var secs = 0
        for (f in files) secs += AudioInfo.durationSec(f.absolutePath)
        if (secs == 0) secs = pm.estimatedSeconds.toInt()
        return TtsResult(files, secs, engine, note)
    }

    // ---------------- Qurilma ovozi (faqat jonli o'qish) ----------------
    private var device: TextToSpeech? = null
    private var deviceReady = false
    private var pending: String? = null

    fun speakDevice(text: String) {
        val t = device
        if (t != null && deviceReady) {
            deviceSpeak(t, text)
            return
        }
        pending = text
        if (t == null) {
            device = TextToSpeech(Store.app) { status ->
                val eng = device
                if (status == TextToSpeech.SUCCESS && eng != null) {
                    deviceReady = true
                    try {
                        eng.language = Locale.forLanguageTag("uz-UZ")
                    } catch (e: Exception) {
                        // til bo'lmasa — qurilmaning standart tili
                    }
                    val p = pending
                    pending = null
                    if (p != null) deviceSpeak(eng, p)
                } else {
                    Ui.toast("Qurilma ovozi ishga tushmadi.")
                }
            }
        }
    }

    private fun deviceSpeak(t: TextToSpeech, text: String) {
        t.stop()
        var first = true
        for (part in Uz.bolaklarga(text, 3500)) {
            val mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            t.speak(part, mode, null, "u" + System.nanoTime())
            first = false
        }
    }

    fun stopDevice() {
        try {
            device?.stop()
        } catch (e: Exception) {
            // e'tiborsiz
        }
    }
}
