// Shared settings for every module. Versions live here so there is one place to read them.
extra["versions"] = mapOf(
    "javalin" to "6.7.0",
    "hikari" to "6.3.0",
    "postgres" to "42.7.7",
    "lettuce" to "6.7.1.RELEASE",
    "bouncycastle" to "1.80",
    "slf4j" to "2.0.17",
    "logback" to "1.5.18",
    "junit" to "5.14.4",
    "testcontainers" to "2.0.5",
)

subprojects {
    apply(plugin = "java")
    group = "dev.concord"
    version = "0.1.0"

    repositories { mavenCentral() }

    @Suppress("UNCHECKED_CAST")
    val v = rootProject.extra["versions"] as Map<String, String>

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:${v["junit"]}"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        // A hung test fails with its name and stack trace instead of silently using up the CI job.
        systemProperty("junit.jupiter.execution.timeout.default", "5m")
        // In a separate thread, so the timeout fails a test even when its thread is blocked on a
        // monitor (an interrupt cannot reach that; GatewayLogTest on a176a2a hung past it).
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
        // A hang outside any test (a JVM that does not exit) fails the task, so CI uploads reports.
        timeout.set(java.time.Duration.ofMinutes(15))
        testLogging {
            // In CI every test's start and end is logged, so a hang shows where it stopped.
            if (System.getenv("CI") != null) events("started", "passed", "skipped", "failed") else events("failed")
            showStandardStreams = System.getenv("TEST_STDOUT") != null
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
