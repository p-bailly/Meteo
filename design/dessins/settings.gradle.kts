// Outil de bureau, hors de l'appli : dessine le coloriage d'Aujourd'hui pour plein de combinaisons, avec le code même
// de l'appli (Compose sur Skia au lieu d'Android). Voir design/README.md, « Coloriages d'Aujourd'hui ».
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "dessins"
