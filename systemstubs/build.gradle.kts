plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine")
}

android {
    namespace = "io.github.sds100.keymapper.systemstubs"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }

        create("debug_release") {
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    annotationProcessor("dev.rikka.tools.refine:annotation-processor:4.3.0")
    compileOnly("dev.rikka.tools.refine:annotation:4.3.0")
    implementation("androidx.annotation:annotation-jvm:1.9.1")
}