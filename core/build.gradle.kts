plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jsoup:jsoup:1.18.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.json:json:20240303")
    api("org.mozilla:rhino-runtime:1.7.15")
    api("com.hierynomus:smbj:0.13.0")
    api("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
