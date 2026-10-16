import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
}

android {
    namespace = "dev.daniel.jigsaw"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.daniel.jigsaw"
        minSdk = 26
        targetSdk = 36
        // Bumping these on master releases them: CI tags v<versionName> and publishes a GitHub Release.
        // versionCode must grow with every release (F-Droid and Android refuse downgrades).
        versionCode = 2
        versionName = "0.2.0"
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
            // Reproducible builds: the APK must not depend on the checkout (VCS info embeds the commit).
            vcsInfo.include = false
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
        unitTests.all { it.useJUnitPlatform() }
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
    // JDK 25 compiles: CI and F-Droid's build server (Debian trixie's openjdk-25, installed by the recipe) have
    // it, and the JDK major shapes the output, so all three must match for reproducible builds. The bytecode
    // stays Java 17.
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
        extraWarnings = true
        progressiveMode = true
        // False positive on writes to Compose `by mutableStateOf` delegates (read through the delegate).
        freeCompilerArgs.add("-Xwarning-level=ASSIGNED_VALUE_IS_NEVER_READ:disabled")
    }
}

detekt {
    buildUponDefaultConfig = true
    // Default-active rules only. detekt 1.23.8 ignored `allRules = true` together with buildUponDefaultConfig
    // (the default config's `active: false` won), so this is the rule set the build has always enforced.
    // detekt 2 does honour allRules; switching it on is a policy change worth its own commit.
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/java", "src/test/java", "build.gradle.kts", rootProject.file("build.gradle.kts"))
}

// Reproducible builds: AGP compiles the libraries' ART baseline profiles (assets/dexopt/baseline.prof)
// non-deterministically, so two builds of one commit differ there and nowhere else. Leaving the profile out
// costs a little startup speed (those paths are compiled on device instead) but lets F-Droid verify our APK.
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required = true
        checkstyle.required = false
        sarif.required = false
        markdown.required = false
    }
}

// `check` runs everything: unit tests, Android Lint, and detekt both with and without type resolution.
tasks.named("check") {
    dependsOn("detekt", "detektMain", "detektTest", "lintDebug")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9 no longer puts a launcher on the test classpath; this one follows the BOM's version.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // A real XmlPullParser for JVM unit tests (android.jar only has stubs).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.6")
    detektPlugins("io.nlopez.compose.rules:detekt:0.6.8")
}
