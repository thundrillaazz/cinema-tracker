plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.michael.cinematracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.michael.cinematracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 48
        versionName = "4.4.2"
        val overrideCode = (project.findProperty("APP_VERSION_CODE") as String?)?.toIntOrNull()
        if (overrideCode != null && overrideCode > 0) {
            versionCode = overrideCode
        }
    }

    val keystoreFile = file("keystore.jks")
    val storePass = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
    val keyAliasEnv = System.getenv("SIGNING_KEY_ALIAS") ?: ""
    val keyPass = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
    val hasPersistentKeystore = keystoreFile.exists() && storePass.isNotEmpty() && keyAliasEnv.isNotEmpty() && keyPass.isNotEmpty()

    signingConfigs {
        if (hasPersistentKeystore) {
            create("persistentRelease") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAliasEnv
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasPersistentKeystore) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
