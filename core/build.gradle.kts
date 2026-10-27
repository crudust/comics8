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
    api(libs.okhttp)
    api(libs.jsoup)
    api(libs.coroutines.core)
    api(libs.json)
    api(libs.rhino)
    api(libs.smbj)
    api(libs.commons.compress)
    api(libs.javif)
    // jsoup 1.23 annotates its API with JSpecify `@Nullable`, declared as a `provided` dependency.
    // Kotlin 2.4 rejects inferred types carrying an annotation class absent from the compile classpath.
    compileOnly(libs.jspecify)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockwebserver)
}
