plugins { alias(libs.plugins.agp.lib) }

android {
    buildFeatures { aidl = true }

    buildTypes { release { isMinifyEnabled = false } }

    sourceSets {
        named("main") {
            java.directories.addAll(listOf("src/main/java", "../libxposed/service/src/main"))
            aidl.directories.addAll(listOf("src/main/aidl", "../libxposed/interface/src/main/aidl"))
        }
    }

    aidlPackagedList += "org/matrix/vector/ipc/LoadedModule.aidl"
    namespace = "org.matrix.vector.daemonservice"
}

dependencies {
    if (!file("../libxposed/service/src/main").isDirectory) {
        api("io.github.libxposed:service:102.0.0")
        api("io.github.libxposed:interface:102.0.0")
    }
    compileOnly(libs.androidx.annotation)
    compileOnly(libs.libxposed.annotation)
    compileOnly(projects.hiddenapi.stubs)
}
