plugins { application }

@Suppress("UNCHECKED_CAST")
val v = rootProject.extra["versions"] as Map<String, String>

dependencies {
    implementation(project(":common"))
    implementation("io.javalin:javalin:${v["javalin"]}")
    runtimeOnly("ch.qos.logback:logback-classic:${v["logback"]}")
    // Cross-service integration tests start real Session instances in-process.
    testImplementation(project(":session"))
    testImplementation(project(":devtools"))
    testImplementation(testFixtures(project(":session")))
    testRuntimeOnly("ch.qos.logback:logback-classic:${v["logback"]}")
}

application { mainClass = "dev.concord.gateway.GatewayMain" }
