plugins { application }

@Suppress("UNCHECKED_CAST")
val v = rootProject.extra["versions"] as Map<String, String>

// Dev-only: seeds users and memberships and mints JWTs signed with the dev key, which
// every non-dev server refuses at startup (see common/Config.java). Never shipped in an image.
dependencies {
    implementation(project(":common"))
    runtimeOnly("ch.qos.logback:logback-classic:${v["logback"]}")
}

application { mainClass = "dev.concord.devtools.Dev" }
