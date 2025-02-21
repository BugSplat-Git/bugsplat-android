plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bugsplat.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "*/arm64-v8a/*.so",
                "*/x86_64/*.so"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/cpp/crashpad/lib")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Add this block to rename the AAR
afterEvaluate {
    android.libraryVariants.forEach { variant ->
        val bundleTask = tasks.named("bundle${variant.name.capitalize()}Aar")
        bundleTask.configure {
            val aarOutputDir = file("${buildDir}/outputs/aar")
            val originalAarName = "app-${variant.name}.aar"
            val newAarName = "bugsplat-android-${variant.name}.aar"
            val originalFile = file("$aarOutputDir/$originalAarName")
            val newFile = file("$aarOutputDir/$newAarName")

            doLast {
                if (originalFile.exists()) {
                    originalFile.renameTo(newFile)
                }
            }
        }
    }
}