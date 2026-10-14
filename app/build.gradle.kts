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
        // CI passes these for releases (tag → name, run number → code); local builds use the defaults.
        versionCode = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("appVersionName").getOrElse("0.1.0")
    }

    androidResources {
        // Keep only the app's own languages; drops the many translations libraries bring along.
        localeFilters += listOf("en", "de")
        // Lists them in the system's per-app language setting (Android 13+); see res/resources.properties.
        generateLocaleConfig = true
    }

    // Release signing comes from the environment (CI secrets); without it the release APK is unsigned.
    val keystore = providers.environmentVariable("RELEASE_KEYSTORE").orNull
    val releaseSigning = keystore?.let {
        signingConfigs.create("release") {
            storeFile = file(it)
            storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            // R8 full mode (AGP default): shrink, optimise and obfuscate code, then drop unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
    }

    packaging {
        resources {
            // Build metadata no code reads at runtime.
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/**/*.kotlin_builtins",
                "kotlin/**",
                "DebugProbesKt.bin",
                "META-INF/com.android.tools/**",
            )
        }
    }

    bundle {
        // The language can be switched in the app, so every translation must ship with it.
        language { enableSplit = false }
    }

    dependenciesInfo {
        // Google-only encrypted dependency report; not needed outside the Play Store.
        includeInApk = false
        includeInBundle = false
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Real implementations of org.json / XmlPullParser for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20250517")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
    detektPlugins("io.nlopez.compose.rules:detekt:0.4.27")
}
