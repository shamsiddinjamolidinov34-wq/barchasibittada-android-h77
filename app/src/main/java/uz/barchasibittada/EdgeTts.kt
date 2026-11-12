package uz.barchasibittada

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge "Read aloud" xizmati. Kalit talab qilmaydi.
 * O'zbekcha ovozlar: Sardor (erkak) va Madina (ayol). Natija — MP3 baytlari.
 */
object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val CHROMIUM = "143.0.3650.75"
    private const val MAJOR = "143"

    @Volatile
    private var skew: Long = 0L

    private class Holder {
        var done = false
        var error: String? = null
        var status = 0
        var dateHeader: String? = null
    }

    fun voiceName(short: String): String = if (short == "madina") "uz-UZ-MadinaNeural" else "uz-UZ-SardorNeural"

    fun rateFor(speed: Int): String = if (speed < 0) "-20%" else if (speed > 0) "+20%" else "+0%"

    private fun longVoice(full: String): String {
        val m = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(full) ?: return full
        val lang = m.groupValues[1]
        val region = m.groupValues[2]
        val name = m.groupValues[3]
        return "Microsoft Server Speech Text to Speech Voice (" + lang + "-" + region + ", " + name + ")"
    }

    private fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + skew + 11644473600L
        ticks -= ticks % 300L
        val value = ticks * 10000000L
        val src = value.toString() + TOKEN
        val digest = MessageDigest.getInstance("SHA-256").digest(src.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        for (b in digest) sb.append("%02X".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun dateString(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date()) + " GMT+0000 (Coordinated Universal Time)"
    }

    private fun randomHex(n: Int): String {
        val chars = "0123456789ABCDEF"
        val sb = StringBuilder()
        val rnd = java.util.Random()
        for (i in 0 until n) sb.append(chars[rnd.nextInt(16)])
        return sb.toString()
    }

    private fun xmlEscape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val c = ch.code
            if (c in 0..8 || c == 11 || c == 12 || c in 14..31) {
                sb.append(' ')
                continue
            }
            if (ch == '&') sb.append("&amp;")
            else if (ch == '<') sb.append("&lt;")
            else if (ch == '>') sb.append("&gt;")
            else if (ch == '"') sb.append("&quot;")
            else if (ch == '\'') sb.append("&apos;")
            else sb.append(ch)
        }
        return sb.toString()
    }

    private fun adjustSkew(dateHeader: String?) {
        if (dateHeader == null) return
        try {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val server = f.parse(dateHeader) ?: return
            skew = server.time / 1000L - System.currentTimeMillis() / 1000L
        } catch (e: Exception) {
            // e'tiborsiz
        }
    }

    /** Matnni MP3 ga aylantiradi. voice: "sardor" yoki "madina". */
    suspend fun synth(text: String, voice: String, rate: String): ByteArray {
        var last: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                return withContext(Dispatchers.IO) { synthOnce(text, voiceName(voice), rate) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                delay(1200L * (attempt + 1))
            }
        }
        val m = last?.message ?: ""
        throw ProviderError(
            "Microsoft ovozi bilan aloqa o'rnatilmadi. " + Safe.mask(m).take(120),
            0, true, "tarmoq"
        )
    }

    private fun synthOnce(text: String, voice: String, rate: String): ByteArray {
        val connId = UUID.randomUUID().toString().replace("-", "")
        val url = BASE + "?TrustedClientToken=" + TOKEN + "&ConnectionId=" + connId +
            "&Sec-MS-GEC=" + secMsGec() + "&Sec-MS-GEC-Version=1-" + CHROMIUM
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
                    MAJOR + ".0.0.0 Safari/537.36 Edg/" + MAJOR + ".0.0.0"
            )
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=" + randomHex(32) + ";")
            .build()

        val out = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val h = Holder()
        val ts = dateString()
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val configMsg = "X-Timestamp:" + ts + "\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\"," +
            "\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='" + longVoice(voice) + "'><prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>" +
            xmlEscape(text) + "</prosody></voice></speak>"
        val ssmlMsg = "X-RequestId:" + reqId + "\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" + ts +
            "Z\r\nPath:ssml\r\n\r\n" + ssml

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(configMsg)
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    h.done = true
                    webSocket.close(1000, null)
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (headLen + 2 > data.size) return
                val head = String(data, 2, headLen, Charsets.UTF_8)
                if (head.contains("Path:audio")) {
                    val start = 2 + headLen
                    if (data.size > start) out.write(data, start, data.size - start)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                h.error = t.message ?: "xato"
                h.status = response?.code ?: 0
                h.dateHeader = response?.header("Date")
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        }

        val ws = Http.client.newWebSocket(req, listener)
        val finished = latch.await(90, TimeUnit.SECONDS)
        try {
            ws.cancel()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        if (!finished) throw IOException("vaqt tugadi")
        if (h.status == 403) {
            adjustSkew(h.dateHeader)
            throw IOException("403: soat farqi sozlandi")
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) throw IOException(h.error ?: "ovoz kelmadi")
        return bytes
    }
}
