plugins {
    kotlin("jvm") version "2.4.20"
    application
}

// Les fichiers du dessin, tels quels : la scène, le ciel, le décor, et les deux petites classes de données qu'ils lisent.
val app = rootDir.resolve("../../app/src/main/java/io/github/pbailly/meteo")
val shared = listOf("ui/SketchScene.kt", "ui/SketchDraw.kt", "ui/SketchDecor.kt", "data/TerrainProfile.kt", "data/Surroundings.kt")

val copyShared by tasks.registering(Sync::class) {
    from(app) { shared.forEach { include(it) } }
    into(layout.buildDirectory.dir("shared/io/github/pbailly/meteo"))
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("src")
        kotlin.srcDir(copyShared.map { layout.buildDirectory.dir("shared").get() })
    }
}

dependencies {
    val compose = "1.11.1"
    implementation("org.jetbrains.compose.ui:ui-graphics-desktop:$compose")
    implementation("org.jetbrains.compose.ui:ui-unit-desktop:$compose")
    implementation("org.jetbrains.compose.ui:ui-geometry-desktop:$compose")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.144.6")
}

application {
    mainClass.set("io.github.pbailly.meteo.ui.DessinsKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    args = listOf(rootDir.resolve("../../app/src/test/resources/terrain/profiles.json").path, rootDir.resolve("build/dessins").path)
}
