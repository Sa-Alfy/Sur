plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.saalfy.sur.downloader"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor needs java.nio desugaring below API 33.
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.all {
            // Network tests (real YouTube requests) only run with -Psur.networkTests=true.
            it.systemProperty(
                "sur.networkTests",
                providers.gradleProperty("sur.networkTests").getOrElse("false"),
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    testImplementation(libs.junit)
}
