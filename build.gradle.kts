plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    // Legge `android/google-services.json` e ne genera le risorse. Serve solo all'app.
    alias(libs.plugins.google.services) apply false
}

group = "dev.mfoot"
version = "0.1.0"
