plugins { application }

@Suppress("UNCHECKED_CAST")
val v = rootProject.extra["versions"] as Map<String, String>

// Dev-only: seeds users, memberships and sessions; refuses to run outside CONCORD_ENV=dev.
// Never shipped in an image.
dependencies {
    implementation(project(":common"))
    runtimeOnly("ch.qos.logback:logback-classic:${v["logback"]}")
}

application { mainClass = "dev.concord.devtools.Dev" }
