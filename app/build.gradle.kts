import io.gitlab.arturbosch.detekt.Detekt

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "dev.daniel.jigsaw"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.daniel.jigsaw"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // org.json / XmlPullParser are used by the sources; return defaults instead of throwing in JVM tests.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkAllWarnings = true
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        checkTestSources = true
        checkGeneratedSources = false
        explainIssues = true
        lintConfig = rootProject.file("config/lint/lint.xml")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors = true
        extraWarnings = true
        progressiveMode = true
        // False positive on writes to Compose `by mutableStateOf` delegates (read through the delegate).
        freeCompilerArgs.add("-Xwarning-level=ASSIGNED_VALUE_IS_NEVER_READ:disabled")
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/java", "src/test/java", "build.gradle.kts", rootProject.file("build.gradle.kts"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required = true
        txt.required = false
        xml.required = false
        sarif.required = false
        md.required = false
    }
}

// `check` runs everything: unit tests, Android Lint, and detekt both with and without type resolution.
tasks.named("check") {
    dependsOn("detekt", "detektMain", "detektTest", "lintDebug")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.07.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Real implementations of org.json / XmlPullParser for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20250517")
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
    detektPlugins("io.nlopez.compose.rules:detekt:0.4.27")
}
