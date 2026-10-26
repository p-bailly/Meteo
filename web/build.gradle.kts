import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Webapp pour iPhone (et tout navigateur) : le même code Kotlin que l'appli Android, compilé en WebAssembly avec
// Compose Multiplatform. Les fichiers de app/ sans dépendance à Android sont repris tels quels (liste [shared]) ;
// java.* et les quelques classes Android qu'ils utilisent sont imités dans src/wasmJsMain/kotlin/java.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/** Fichiers de l'appli Android compilés aussi pour le web (chemins sous app/src/main/java/io/github/pbailly/meteo). */
val shared = listOf(
    "data/AirQuality.kt",
    "data/Altitude.kt",
    "data/Hedge.kt",
    "data/Models.kt",
    "data/Moon.kt",
    "data/Nominatim.kt",
    "data/OpenMeteoApi.kt",
    "data/Outlook.kt",
    "data/PlacesStore.kt",
    "data/Predictability.kt",
    "data/SettingsStore.kt",
    "data/SpaceWeather.kt",
    "data/Surroundings.kt",
    "data/SurroundingsQuery.kt",
    "data/Terrain.kt",
    "data/TerrainProfile.kt",
    "data/WeatherRepository.kt",
    "ui/AltitudeScreen.kt",
    "ui/ChartKit.kt",
    "ui/CompareScreen.kt",
    "ui/DayDetailScreen.kt",
    "ui/DaysScreen.kt",
    "ui/DaysTab.kt",
    "ui/Format.kt",
    "ui/Glacier.kt",
    "ui/MeteogramTab.kt",
    "ui/MeteoViewModel.kt",
    "ui/MeteogramsScreen.kt",
    "ui/MultiModelTab.kt",
    "ui/PlacesScreen.kt",
    "ui/SketchDecor.kt",
    "ui/SketchDraw.kt",
    "ui/SketchPostcard.kt",
    "ui/SketchScene.kt",
    "ui/TodayScreen.kt",
    "ui/WeatherIcon.kt",
    "ui/WeatherSketch.kt",
    "ui/theme/Scales.kt",
    "ui/theme/Theme.kt",
)

val syncShared by tasks.registering(Sync::class) {
    from("../app/src/main/java/io/github/pbailly/meteo") { include(shared) }
    into(layout.buildDirectory.dir("shared/io/github/pbailly/meteo"))
}

/** Les polices de l'appli, servies avec la page. */
val copyFonts by tasks.registering(Copy::class) {
    from("../app/src/main/res/font")
    into(layout.buildDirectory.dir("webfonts/fonts"))
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("meteo")
        browser {
            commonWebpackConfig { outputFileName = "meteo.js" }
        }
        binaries.executable()
    }
    sourceSets {
        wasmJsMain {
            kotlin.srcDir(syncShared.map { layout.buildDirectory.dir("shared").get() })
            resources.srcDir(copyFonts.map { layout.buildDirectory.dir("webfonts").get() })
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.materialIconsExtended)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.jb.lifecycle.viewmodel.compose)
                implementation(libs.jb.lifecycle.runtime.compose)
            }
        }
    }
}

/**
 * La webapp prête à publier (GitHub Pages) dans build/site : les modules JavaScript et WebAssembly tels que le
 * compilateur les produit (sans webpack : le navigateur les charge directement), Skia (skiko), js-joda (dates de
 * kotlinx-datetime, dont dépend Material 3), les polices, la page, les icônes et le service worker hors ligne.
 */
val site by tasks.registering(Sync::class) {
    group = "build"
    dependsOn("compileProductionExecutableKotlinWasmJsOptimize", ":kotlinWasmNpmInstall")
    val npm = rootProject.layout.buildDirectory.dir("wasm/node_modules")
    from(layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/optimized")) { include("*.mjs", "*.wasm") }
    from(npm.map { it.dir("skiko-js-wasm-runtime") }) { include("skiko.mjs", "skiko.wasm") }
    from(npm.map { it.file("@js-joda/core/dist/js-joda.esm.js") })
    from(copyFonts) { into("fonts") }
    // Chaque construction change la version du service worker : les téléphones téléchargent la nouvelle.
    val version = System.getenv("GITHUB_SHA")?.take(12) ?: System.currentTimeMillis().toString()
    inputs.property("version", version)
    from("src/wasmJsMain/resources") {
        filesMatching("sw.js") { filter { it.replace("@VERSION@", version) } }
    }
    into(layout.buildDirectory.dir("site"))
}
