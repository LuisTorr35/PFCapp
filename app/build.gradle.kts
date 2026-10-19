plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.pfc_app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.pfc_app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime trae su biblioteca nativa para cuatro arquitecturas (unos 35 MB
        // cada una). Se dejan las de teléfonos de 64 y 32 bits y la del emulador x86_64.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // Bibliotecas nativas comprimidas dentro del APK: lo reduce a menos de la mitad.
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        // El modelo se lee desde assets como bytes: no comprimir el .onnx.
        noCompress += "onnx"
    }
    testOptions {
        unitTests {
            // android.util.Log devuelve valores por defecto en las pruebas de JVM.
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Las pruebas de JVM corren el mismo .onnx con ONNX Runtime de escritorio
    // (misma API ai.onnxruntime) y leen el contrato con org.json real.
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// En las pruebas de JVM se usa onnxruntime de escritorio en lugar del AAR de
// Android, que trae bibliotecas nativas que la JVM de Windows no puede cargar.
configurations.configureEach {
    if (name.contains("UnitTest") && name.endsWith("Classpath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
