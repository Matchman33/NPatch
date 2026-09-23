plugins { alias(libs.plugins.agp.app) }

android {
    namespace = "example.npatch.smoke"
    defaultConfig { applicationId = "example.npatch.smoke" }
}

dependencies { implementation("androidx.graphics:graphics-path:1.0.1") }
