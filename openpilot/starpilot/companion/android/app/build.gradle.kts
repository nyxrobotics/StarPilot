plugins { id("com.android.application") }

android {
    namespace = "link.firestar.companion"
    compileSdk = 37
    defaultConfig {
        applicationId = "link.firestar.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 45
        versionName = "1.1.43"
    }
    signingConfigs {
        create("companion") {
            storeFile = file(System.getenv("COMPANION_KEYSTORE") ?: "${System.getProperty("user.home")}/.starpilot/companion.jks")
            storePassword = System.getenv("COMPANION_KEY_PASSWORD")
            keyAlias = "companion"
            keyPassword = System.getenv("COMPANION_KEY_PASSWORD")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("companion") } }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    // XZ for Java (0BSD / public domain): the `xz` encoding of a route, the fallback when write without response is not available (docs/dev/bluetooth-link.md section 6, L4).
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
}

