plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation("org.postgresql:postgresql:42.7.4")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.mfoot.tick.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Un jar eseguibile con tutte le dipendenze dentro: su GitHub Actions si lancia con
// `java -jar` invece di far ripartire Gradle a ogni tick, il che taglia l'avvio da
// una trentina di secondi a pochi.
tasks.register<Jar>("fatJar") {
    archiveFileName.set("world-tick.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    manifest { attributes["Main-Class"] = "dev.mfoot.tick.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}
