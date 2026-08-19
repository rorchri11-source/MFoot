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
        // Il numero di versione sale a ogni build, e la data ne fa parte.
        //
        // Serve a rispondere a una domanda che si e' gia' posta e che costava un'ora ogni
        // volta: "l'APK che sto provando contiene la correzione o no?". Con versionCode
        // fisso a 1 e nessuna versione scritta nell'app non c'era modo di saperlo, e si
        // finiva per discutere di difetti gia' corretti guardando una build vecchia.
        //
        // La versione compare in fondo al menu laterale, non in una schermata "info" che
        // nessuno apre.
        versionCode = 14
        versionName = "0.14.0"

        buildConfigField("String", "SUPABASE_URL", "\"${secret("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${secret("supabase.key")}\"")
    }

    /**
     * La firma di rilascio.
     *
     * Un APK firmato con la chiave di debug e' marcato `debuggable`, e Android lo tratta
     * di conseguenza: avvisi piu' insistenti, e su alcune versioni il rifiuto di
     * installarlo del tutto. Con una chiave propria l'app risulta una normale
     * applicazione firmata.
     *
     * Il keystore vive **fuori dal repository**, che e' pubblico, e il percorso arriva da
     * `local.properties`. Chi non ce l'ha puo' comunque compilare la versione di debug:
     * la configurazione si applica solo se il file esiste davvero, altrimenti Gradle
     * fallirebbe per chiunque cloni il progetto.
     */
    val keystorePath = secret("keystore.file")
    val hasKeystore = keystorePath.isNotBlank() && File(keystorePath).exists()

    if (hasKeystore) {
        signingConfigs {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = secret("keystore.password")
                keyAlias = secret("keystore.alias", "mfoot")
                keyPassword = secret("keystore.password")

                // Schema V2 e V3: dalla verifica dell'intero archivio in poi Android
                // controlla molto piu' in fretta e con piu' certezza chi ha firmato.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
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
