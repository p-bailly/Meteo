plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.pbailly.meteo"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.pbailly.meteo"
        minSdk = 26
        targetSdk = 36
        // Sur GitHub Actions, chaque build a un numéro plus grand : le téléphone accepte la mise à jour.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.$versionCode"
        // Bibliothèques natives de la carte : seulement les processeurs des téléphones, pour un APK plus léger.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Clé de signature d'usage personnel, versionnée pour que chaque APK puisse mettre à jour le précédent.
        create("perso") {
            storeFile = file("meteo-perso.jks")
            storePassword = "meteo-perso"
            keyAlias = "meteo"
            keyPassword = "meteo-perso"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Bibliothèques natives de lz4-java (dépendance de jhdf) pour ordinateurs : inutiles sur Android.
            excludes += "net/jpountz/util/**"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maplibre.android)
    implementation(libs.jhdf)
    implementation(libs.androidx.glance.appwidget)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
