plugins { base }

listOf("Debug", "Release").forEach { variant ->
    val name = variant.lowercase()
    val runtime = tasks.register<Zip>("runtime$variant") {
        dependsOn(":patch-loader:copy$variant", ":meta-loader:copy$variant")
        from(rootProject.layout.projectDirectory.dir("out/assets/$name/npatch")) {
            include("loader.bin", "so/**/libnpatch.so")
        }
        archiveFileName.set("runtime.zip")
        destinationDirectory.set(rootProject.layout.projectDirectory.dir("out/assets/$name/wrapper"))
    }
    tasks.register<Copy>("copy$variant") {
        dependsOn(":meta-loader:copy$variant", runtime)
        from(rootProject.layout.projectDirectory.file("out/assets/$name/npatch/metaloader.dex"))
        rename("metaloader.dex", "loader.dex")
        into(rootProject.layout.projectDirectory.dir("out/assets/$name/wrapper"))
    }
}
