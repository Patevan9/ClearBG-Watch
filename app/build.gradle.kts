import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: a SEPARATE installer key (not the Glucose Glance key).
// It is NEVER committed. Its location and passwords come from
// installer/keystore.properties (git-ignored), e.g.:
//   storeFile=/path/to/glucose-installer.keystore
//   storePassword=...
//   keyAlias=installer
//   keyPassword=...
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.patevan9.glucose.installer"
    compileSdk = 37 // required by Kadb

    defaultConfig {
        applicationId = "io.github.patevan9.glucose.installer"
        minSdk = 28 // Android 9 phones and newer
        targetSdk = 35
        versionCode = 101
        versionName = "1.0.1"
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No code shrinking: keeps the pairing and crypto libraries intact.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Library license notes with the same name; keep one copy so the build works.
    packaging {
        resources { pickFirsts += listOf("META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.md") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Kadb: Apache-2.0. Its pairing part, spake2-java, is GPL-3.0, so this
    // installer is distributed as open source under GPL-3.0 (see README.md).
    implementation("com.flyfishxu:kadb:2.1.5")
    implementation("com.flyfishxu:kadb-mdns:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}

// ---- Pack the official Glucose Glance files (from the watch-latest release) ----
// Put glucose-app.apk and glucose-watchface.apk in installer/bundle/ (not committed).
// The installer checks at run time that both are signed with the Glucose Glance key.
val packedDir = layout.buildDirectory.dir("generated/glucose")
val packGlucose = tasks.register<Copy>("packGlucose") {
    from(rootProject.file("bundle")) { include("glucose-app.apk", "glucose-watchface.apk") }
    into(packedDir)
    doFirst {
        listOf("glucose-app.apk", "glucose-watchface.apk").forEach {
            check(rootProject.file("bundle/$it").isFile) { "Missing installer/bundle/$it (download it from the watch-latest release)" }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(packedDir.get().asFile)
tasks.named("preBuild") { dependsOn(packGlucose) }
