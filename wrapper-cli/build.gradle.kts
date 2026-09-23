plugins { id("java") }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(projects.wrapperPatch)
    implementation(npatch.beust.jcommander)
}

tasks.register<Jar>("fatJar") {
    dependsOn(":wrapper-loader:copyRelease", "classes")
    dependsOn(configurations.runtimeClasspath)
    archiveFileName.set("apk-wrapper.jar")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("out/wrapper"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "top.nkbe.npatch.wrappercli.WrapperCli") }
    from(sourceSets.main.get().output)
    from(provider { configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    into("assets") {
        from(rootProject.file("out/assets/release")) { include("wrapper/loader.dex", "wrapper/runtime.zip") }
        from(rootProject.file("jar/src/main/assets")) { include("npatch.key") }
    }
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC", "META-INF/versions/**")
}
