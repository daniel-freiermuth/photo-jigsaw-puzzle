buildscript {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.4.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
        // Pinned pre-release: detekt 1.x is unmaintained; move to 2.0.0 once it is final.
        classpath("dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6")
    }
}
