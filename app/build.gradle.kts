import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    jacoco
}

// Release-signing material is resolved (in priority order) from a local,
// git-ignored keystore.properties or from environment variables (CI secrets).
// Nothing is hard-coded or committed; when none is present the release build is
// simply left unsigned so a secret-less checkout still builds.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(key: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(key))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.questloop.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.questloop.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Route instrumented-test output (screenshots) through Test Storage so AGP
        // pulls it into build/outputs/connected_android_test_additional_output.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Only register a "release" config when a keystore is actually present;
        // buildTypes.release picks it up via findByName, falling back to unsigned.
        val storeFilePath = signingValue("RELEASE_KEYSTORE_FILE")
        if (storeFilePath != null && file(storeFilePath).exists()) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = signingValue("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Produce JaCoCo execution data from JVM unit tests for the coverage report.
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with the release key when its material is available (CI secrets
            // or a local keystore.properties); otherwise leave unsigned so a
            // secret-less build still completes. See signingConfigs below.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Make the exported Room schemas available to instrumented tests so
    // MigrationTestHelper can open each historical version and validate the
    // migrations against the real schema JSON that ships in the repo.
    sourceSets.getByName("androidTest").assets.srcDirs("$projectDir/schemas")
}

// Where Room writes exported schemas (for migrations and migration tests).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

jacoco {
    toolVersion = "0.8.12"
}

// Coverage for the app module from JVM/Robolectric unit tests. Generated code
// (Room/Compose/serializers/manifest) and pure framework glue that can't be
// exercised without an emulator (Application, DI wiring, theme, the Glance
// widget, and boot/notification BroadcastReceivers) are excluded so the metric
// reflects logic we can actually unit-test: ViewModels, data, and screens.
private val appCoverageExclusions = listOf(
    "**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    "**/*Test*.*", "**/databinding/**",
    "**/*_Impl*.*", "**/*\$serializer.*", "**/ComposableSingletons*.*",
    "**/*_Factory*.*", "**/*_MembersInjector*.*",
    // Framework glue not exercised by JVM unit tests:
    "**/MainActivity*.*", "**/QuestLoopApplication*.*",
    "**/di/**", "**/ui/theme/**", "**/widget/**",
    "**/reminders/BootReceiver*.*", "**/reminders/ReminderActionReceiver*.*",
)

private fun coverageClassDirs() = files(
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile) { exclude(appCoverageExclusions) },
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes").get().asFile) { exclude(appCoverageExclusions) },
)

private fun coverageExecData() = fileTree(layout.buildDirectory.get().asFile) {
    include(
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
        "jacoco/testDebugUnitTest.exec",
        "**/testDebugUnitTest.exec",
    )
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(coverageClassDirs())
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(coverageExecData())
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    group = "verification"
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(coverageClassDirs())
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(coverageExecData())
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    // Test Storage service: collects screenshots written during instrumented tests.
    androidTestUtil(libs.androidx.test.services)
}
