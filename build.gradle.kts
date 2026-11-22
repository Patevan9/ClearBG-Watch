plugins {
    // Newer tools than ../wear on purpose: the Kadb library needs Android API 37
    // and Kotlin 2.4. The watch app keeps its own, unchanged tools.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
