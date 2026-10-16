plugins {
    application
    `java-test-fixtures`
}

@Suppress("UNCHECKED_CAST")
val v = rootProject.extra["versions"] as Map<String, String>

dependencies {
    implementation(project(":common"))
    implementation("io.javalin:javalin:${v["javalin"]}")
    runtimeOnly("ch.qos.logback:logback-classic:${v["logback"]}")
    // Shared test infrastructure (Postgres, Redis, Toxiproxy containers), also used by gateway tests.
    testFixturesApi(project(":common"))
    testFixturesApi("org.testcontainers:testcontainers:${v["testcontainers"]}")
    testFixturesApi("org.testcontainers:testcontainers-postgresql:${v["testcontainers"]}")
    testFixturesApi("org.testcontainers:testcontainers-toxiproxy:${v["testcontainers"]}")
    testRuntimeOnly("ch.qos.logback:logback-classic:${v["logback"]}")
}

application { mainClass = "dev.concord.session.SessionMain" }
