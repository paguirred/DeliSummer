plugins {
    // AGP 9 trae soporte de Kotlin incorporado: aplicar ademas
    // org.jetbrains.kotlin.android duplica la extension y falla el build.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cl.aguirre.cuaderno"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.aguirre.cuaderno"
        // 29 es el piso real del renderizado con front buffer, que es lo que da
        // la latencia baja. La tablet corre Android 16, asi que no limita nada.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Firmado con la clave de depuracion a proposito: la app es de uso
            // familiar y no va a una tienda, pero el build release tiene que ser
            // instalable para poder medir la latencia real. Un debug corre sin
            // R8 y con debuggable activo, y en una app de tinta eso se siente.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Con Kotlin incorporado, jvmTarget hereda de targetCompatibility, asi que
    // declararlo aparte seria repetirse y arriesgar que se desincronicen.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Motor de tinta
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.authoring.compose)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.brush.compose)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.geometry.compose)
    implementation(libs.androidx.ink.nativeloader)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.storage)
    implementation(libs.androidx.ink.strokes)

    implementation(libs.androidx.graphics.core)
    implementation(libs.androidx.input.motionprediction)
}
