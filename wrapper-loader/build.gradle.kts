plugins { base }

listOf("Debug", "Release").forEach { variant ->
    val name = variant.lowercase()
    val runtime = tasks.register<Zip>("runtime$variant") {
        dependsOn(":patch-loader:copy$variant", ":meta-loader:copy$variant")
        from(rootProject.layout.projectDirectory.dir("out/assets/$name/npatch")) {
            include("loader.bin", "so/**/libnpatch.so")
        }
        from(rootProject.layout.projectDirectory.dir("gadget/runtime")) {
            include(
                "arm64-v8a/libnpatch-gadget.so",
                "arm64-v8a/libnpatch-gadget.config.so",
                "arm64-v8a/libscript.so",
                "x86_64/libnpatch-gadget.so",
                "x86_64/libnpatch-gadget.config.so",
                "x86_64/libscript.so",
            )
            into("gadget")
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
