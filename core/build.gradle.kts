plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation("junit:junit:4.13.2")
}
application { mainClass.set("com.hgtv.star.core.ProbeKt") }
// This client deliberately omits HGTV's emoji images and has no danmaku UI.
// Keep parser tests, but the upstream UI asset inventory is not part of this APK.
tasks.test {
    filter { excludeTestsMatching("*.DanmakuEmojiTest.every tag resolves to a glyph that has an image on disk") }
}
