package uz.barchasibittada

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VoiceScreen() {
    val engine = Settings.ttsEngine
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Heading("Ovoz")
        Hint("Matn ovozga aylantirilganda ishlatiladigan dvigatel, ovoz va tezlik.")
        StatusBars()

        SubHeading("Ovoz dvigateli")
        Choice(
            "Microsoft (kalitsiz, o'zbekcha)", engine == "microsoft",
            { Settings.ttsEngine = "microsoft" },
            hint = "Sardor (erkak) va Madina (ayol) — haqiqiy o'zbek ovozlari. Internet kerak."
        )
        Choice(
            "Gemini ovozi", engine == "gemini",
            { Settings.ttsEngine = "gemini" },
            hint = if (Keys.has(Prov.GEMINI)) "Kalit bor." else "Gemini kaliti kerak. Kalit bo'lmasa Microsoft ishlatiladi."
        )
        Choice(
            "ChatGPT ovozi", engine == "openai",
            { Settings.ttsEngine = "openai" },
            hint = if (Keys.has(Prov.OPENAI)) "Kalit bor." else "ChatGPT kaliti kerak. Kalit bo'lmasa Microsoft ishlatiladi."
        )
        Choice(
            "Grok ovozi", engine == "xai",
            { Settings.ttsEngine = "xai" },
            hint = if (Keys.has(Prov.XAI)) "Kalit bor. Grok ovozlari ko'p tilni o'qiydi." else "Grok kaliti kerak. Kalit bo'lmasa Microsoft ishlatiladi."
        )
        Choice(
            "Qurilma ovozi", engine == "device",
            { Settings.ttsEngine = "device" },
            hint = "Telefondagi ovoz xizmati. Faqat o'qib berish uchun; audio fayl yaratishda Microsoft ishlatiladi."
        )

        if (engine == "microsoft") {
            SubHeading("Microsoft ovozi")
            Choice("Sardor (erkak)", Settings.msVoice == "sardor", { Settings.msVoice = "sardor" })
            Choice("Madina (ayol)", Settings.msVoice == "madina", { Settings.msVoice = "madina" })
        } else if (engine == "gemini") {
            SubHeading("Gemini ovozi")
            Dropdown("Ovoz", Settings.geminiVoice, Models.geminiVoices) { Settings.geminiVoice = it }
        } else if (engine == "openai") {
            SubHeading("ChatGPT ovozi")
            Dropdown("Ovoz", Settings.openaiVoice, Models.openaiVoices) { Settings.openaiVoice = it }
        } else if (engine == "xai") {
            SubHeading("Grok ovozi")
            Dropdown("Ovoz", Settings.xaiVoice, Models.xaiVoices) { Settings.xaiVoice = it }
        }

        SubHeading("Gapirish tezligi (Microsoft)")
        Choice("Sekin", Settings.speechSpeed < 0, { Settings.speechSpeed = -1 })
        Choice("Oddiy", Settings.speechSpeed == 0, { Settings.speechSpeed = 0 })
        Choice("Tez", Settings.speechSpeed > 0, { Settings.speechSpeed = 1 })

        SubHeading("Qo'shimcha")
        Toggle(
            "Har javobga ovoz qo'shish",
            Settings.ttsAuto,
            { Settings.ttsAuto = it },
            hint = "Suhbatdagi har bir javob avtomatik o'qib beriladi. Ekran o'quvchi bilan birga ishlatsangiz, ovozlar qo'shilib ketishi mumkin."
        )
        Toggle(
            "Ovoz rejimi",
            Settings.audioMode,
            { Settings.audioMode = it },
            hint = "Yoqilsa, suhbatga yozgan matningiz javob berilmasdan, to'g'ridan-to'g'ri o'qib beriladi."
        )
        Toggle(
            "Katta audiokitobni bo'laklab saqlash",
            Settings.bookParts,
            { Settings.bookParts = it },
            hint = "O'chiq bo'lsa, butun matn bitta audio faylga yig'iladi. Hajm chegarasi yo'q."
        )
        if (Settings.bookParts) {
            Dropdown("Har bir bo'lak", Settings.partMinutes.toString() + " daqiqa", listOf("30 daqiqa", "60 daqiqa", "120 daqiqa")) {
                Settings.partMinutes = it.substringBefore(' ').toIntOrNull() ?: 60
            }
        }

        SubHeading("Sinab ko'rish")
        Btn("Ovozni sinab ko'rish", { Engine.testVoice() })
        if (Player.current.isNotEmpty()) {
            OBtn("Ijroni to'xtatish", { Player.stop() })
        }
    }
}
