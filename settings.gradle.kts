// I plugin si risolvono da un blocco separato rispetto alle dipendenze: senza google()
// qui dentro, il plugin Android non viene trovato anche se google() e' gia' dichiarato
// piu' sotto.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mfoot"

include(":core")
include(":tick")
include(":android")
