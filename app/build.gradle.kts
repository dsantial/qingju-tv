plugins { id("com.android.application"); kotlin("android") }
android {
 namespace = "local.qingju.tv"
 compileSdk = 36
 buildToolsVersion = "36.0.0"
 defaultConfig {
  applicationId = "local.qingju.tv"
  minSdk = 21
  targetSdk = 35
  versionCode = 4
  versionName = "0.4.0"
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
 kotlinOptions { jvmTarget = "17" }
 packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
dependencies {
 implementation(project(":core")) { exclude(group = "org.json") }
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("androidx.media3:media3-exoplayer:1.4.1")
 implementation("androidx.media3:media3-ui:1.4.1")
 coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
