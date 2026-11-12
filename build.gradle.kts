// Android Gradle Plugin 8.7.3 uchun Gradle 8.9 yoki undan yuqori kerak.
// Bu loyihada Gradle 8.10.2 ishlatiladi (gradle/wrapper va .github/workflows ga qarang).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
