package uz.barchasibittada

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Throwable) {
            // PDF matn o'qish ishlamasa ham ilova ochiladi
        }
        Engine.init()
    }
}
