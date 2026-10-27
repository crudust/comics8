plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

// The AGP/Kotlin toolchain classpath is build-only (none of it is packaged into the app), but it is
// still a real dependency graph, so Dependabot reports on it. `pluginManagement` cannot raise plugin
// transitives, so the versions we want are *declared* here rather than forced after resolution --
// declaring is what Dependabot reads and what Gradle's normal conflict resolution honours:
//   - KGP: AGP 9 compiles Android modules with built-in Kotlin and would otherwise fall back to the
//     bundled 2.2.10, which still carries GHSA-r937-wjx7-w2jp
//   - bouncycastle: AGP pins 1.80.2 (advisories need >= 1.85)
//   - commons-compress: AGP pins 1.27.1, whose commons-lang3 3.16.0 is below the 3.18.0 fix
//   - jose4j 0.9.6 and jdom2 2.0.6.1 are the first patched releases of what AGP ships
//   - httpclient: declared so the graph never drops to the pre-4.5.13 AGP range
// guava (33.4.0) and protobuf-java (3.25.5) already arrive patched from AGP 9, and AGP 9 no longer
// pulls gRPC, so netty needs no entry at all.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("org.apache.commons:commons-compress:1.28.0")
        classpath("org.apache.commons:commons-lang3:3.21.0")
        classpath("org.apache.httpcomponents:httpclient:4.5.14")
        classpath("org.bitbucket.b_c:jose4j:0.9.6")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.85")
        classpath("org.bouncycastle:bcprov-jdk18on:1.85")
        classpath("org.jdom:jdom2:2.0.6.1")
    }
}
