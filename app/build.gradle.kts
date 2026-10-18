plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.psychconnect"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    defaultConfig {
        applicationId = "com.psychconnect"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "0.6.7"
        val supportUrl = providers.gradleProperty("psychconnectSupportUrl").orElse("https://buymeacoffee.com/farfarspelar").get().replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SUPPORT_URL", "\"$supportUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
