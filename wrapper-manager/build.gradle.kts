import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import java.security.MessageDigest

abstract class CollectWrapperAssets : DefaultTask() {
    @get:InputFile abstract val loaderFile: RegularFileProperty
    @get:InputFile abstract val runtimeFile: RegularFileProperty
    @get:InputFile abstract val signingKey: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun collect() {
        val target = outputDirectory.get().asFile
        target.resolve("wrapper").mkdirs()
        loaderFile.get().asFile.copyTo(target.resolve("wrapper/loader.dex"), overwrite = true)
        runtimeFile.get().asFile.copyTo(target.resolve("wrapper/runtime.zip"), overwrite = true)
        signingKey.get().asFile.copyTo(target.resolve("npatch.key"), overwrite = true)
    }
}

plugins {
    alias(libs.plugins.agp.app)
    alias(npatch.plugins.kotlin.android)
    alias(npatch.plugins.compose.compiler)
}

android {
    namespace = "top.nkbe.npatch.wrappermanager"
    defaultConfig { applicationId = "top.nkbe.npatch.wrappermanager" }
    buildFeatures { compose = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging.resources.excludes += setOf("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**")
}

androidComponents.onVariants { variant ->
    val capped = variant.name.replaceFirstChar { it.uppercase() }
    val assets = tasks.register<CollectWrapperAssets>("copy${capped}WrapperAssets") {
        dependsOn(":wrapper-loader:copy$capped")
        signingKey.set(rootProject.layout.projectDirectory.file("manager/src/main/assets/npatch.key"))
        loaderFile.set(rootProject.layout.projectDirectory.file("out/assets/${variant.name}/wrapper/loader.dex"))
        runtimeFile.set(rootProject.layout.projectDirectory.file("out/assets/${variant.name}/wrapper/runtime.zip"))
        outputDirectory.set(layout.buildDirectory.dir("generated/wrapperAssets/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(assets, CollectWrapperAssets::outputDirectory)
}

dependencies {
    implementation(projects.wrapperPatch)
    implementation(projects.share.java)
    implementation(platform(npatch.androidx.compose.bom))
    implementation(npatch.androidx.activity.compose)
    implementation(npatch.androidx.compose.material3)
    implementation(npatch.androidx.compose.material.icons.extended)
    implementation(npatch.androidx.core.ktx)
    implementation(npatch.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}

tasks.register("collectReleaseArtifacts") {
    dependsOn("assembleRelease", ":wrapper-cli:fatJar")
    val version = providers.gradleProperty("apkLoomVersionName").get()
    val local = providers.gradleProperty("allowDebugSigning").orNull == "true"
    val label = version + if (local) "-local" else ""
    val destination = rootProject.layout.projectDirectory.dir("out/releases/$label")
    outputs.upToDateWhen { false }
    doLast {
        val directory = destination.asFile.apply { mkdirs() }
        val apk = layout.buildDirectory.file("outputs/apk/release/wrapper-manager-release.apk").get().asFile
        val jar = rootProject.file("out/wrapper/apkloom-cli.jar")
        val files = listOf(
            apk.copyTo(directory.resolve("APK-Loom-$label.apk"), overwrite = true),
            jar.copyTo(directory.resolve("apkloom-cli-$label.jar"), overwrite = true),
        )
        directory.resolve("SHA256SUMS.txt").writeText(files.joinToString("\n", postfix = "\n") { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } + "  " + file.name
        })
        val revision = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        val dirty = providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get().isNotBlank()
        directory.resolve("BUILD.txt").writeText("version=$label\nversionCode=${rootProject.extra["verCode"]}\ncommit=$revision\ndirty=$dirty\nsigning=${if (local) "local-debug" else "release"}\n")
    }
}
