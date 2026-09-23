plugins { id("java-library") }

java {
    sourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
    targetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion
}

dependencies {
    api(projects.apkzlib)
    implementation(projects.share.java)
    implementation("vector:axml")
    implementation(npatch.google.gson)
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    testImplementation("junit:junit:4.13.2")
    testImplementation("vector:axml")
    testImplementation(projects.share.java)
    testImplementation("org.bouncycastle:bcpkix-jdk15on:1.70")
}

tasks.test {
    dependsOn(":wrapper-loader:copyRelease")
    systemProperty("repoRoot", rootProject.projectDir.absolutePath)
}
