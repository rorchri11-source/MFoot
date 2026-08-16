// Da AGP 9.0 il supporto Kotlin e' integrato nel plugin Android: applicare anche
// org.jetbrains.kotlin.android fa fallire la configurazione.
// https://kotl.in/gradle/agp-built-in-kotlin
import java.util.Properties

// Da AGP 9.0 il supporto Kotlin e' integrato nel plugin Android: applicare anche
// org.jetbrains.kotlin.android fa fallire la configurazione.
// https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Le credenziali Supabase arrivano da `local.properties`, che git ignora.
 *
 * La chiave pubblicabile non e' un segreto come una password — Supabase stessa dice che
 * si puo' condividere, e la difesa vera sono le Row Level Security. Ma il repository e'
 * pubblico, e lasciarcela dentro significherebbe che chiunque puo' bersagliare il
 * progetto e consumarne i limiti. Tenerla fuori costa una riga.
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String, fallback: String = ""): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: fallback)

android {
    namespace = "dev.mfoot.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.mfoot.android"
        // minSdk 26: `core` usa java.time ovunque per il calendario, e sotto questo
        // livello servirebbe il desugaring.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${secret("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${secret("supabase.key")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Il motore di gioco e' la stessa identica libreria che gira sul tick: e' cosi' che
    // il telefono puo' generare il mondo in locale, in millisecondi, invece di aspettare
    // che il server ci pensi al prossimo giro.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
