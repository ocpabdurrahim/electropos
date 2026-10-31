import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.time.Duration

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.google.services)
}

// The app's version. Raising it is all a release takes: once the change is on
// main and CI's tests pass, CI publishes vX.Y.Z by itself and phones update.
val appVersionName = "1.0.0"

// Android's version code follows from the name: 1.2.3 -> 10203 (minor and patch stay below 100).
val appVersionCode = appVersionName.split('.').map { it.toInt() }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// Where the app looks for updates (its GitHub releases).
val updateApiUrl = "https://api.github.com/repos/ocpabdurrahim/electropos/releases/latest"

// Release signing comes from environment variables, which CI fills from the
// repository secrets. Without them a release build falls back to the debug key:
// it still installs, but it cannot update an APK signed with the real key.
val releaseKeystore: File? = System.getenv("EPOS_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.exists() }

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "io.github.ocpabdurrahim.electropos"
    minSdk = 24
    targetSdk = 36
    // The overrides exist for CI's self-update test, which builds a "newer" copy.
    versionCode = (findProperty("versionCodeOverride") as String?)?.toInt() ?: appVersionCode
    versionName = (findProperty("versionNameOverride") as String?) ?: appVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "UPDATE_API_URL", "\"$updateApiUrl\"")
  }

  signingConfigs {
    if (releaseKeystore != null) {
      create("release") {
        storeFile = releaseKeystore
        storePassword = System.getenv("EPOS_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("EPOS_KEY_ALIAS")
        keyPassword = System.getenv("EPOS_KEY_PASSWORD")
      }
    }
    // A fixed debug key in the project (git-ignored) so test builds update each other
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debugConfig")
      buildConfigField("String", "FIREBASE_EMULATOR_HOST", "\"\"")
    }
    debug {
      // Lets a test build sit next to the real app on the same phone.
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
      signingConfig = signingConfigs.getByName("debugConfig")
      // -PfirebaseEmulatorHost=10.0.2.2 points a test build at the local Firebase
      // emulators (10.0.2.2 is the computer, seen from an Android emulator).
      buildConfigField("String", "FIREBASE_EMULATOR_HOST", "\"${findProperty("firebaseEmulatorHost") ?: ""}\"")
      // -PupdateApiUrl=... lets a test build update itself from a local server.
      buildConfigField("String", "UPDATE_API_URL", "\"${findProperty("updateApiUrl") ?: updateApiUrl}\"")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  // The migration tests (MigrationTestHelper under Robolectric) read Room's exported schemas from the
  // debug variant's assets (Robolectric has no test-only assets); release builds don't carry them
  sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Room writes each schema version to app/schemas (kept in git); the migration tests read them.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  // Barcode reading for the scanner screen (model bundled in the APK, works offline)
  implementation(libs.mlkit.barcode.scanning)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // Firestore:
  implementation(libs.firebase.firestore)

  // Firebase Auth and Google Sign-In via Credential Manager:
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.kotlinx.coroutines.play.services)
  // App self-update: background checks and installing once the app is closed
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  // MigrationTestHelper for the Room migration tests
  testImplementation(libs.androidx.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.withType<Test> {
    // Run every test (report all failures) and allow time for Robolectric's first-run downloads
    failFast = false
    timeout.set(Duration.ofMinutes(15))
    testLogging {
        events("passed", "failed", "skipped")
    }
}

