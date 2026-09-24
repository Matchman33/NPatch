import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

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
