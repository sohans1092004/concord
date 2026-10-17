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
        testLogging {
            events("failed")
            showStandardStreams = System.getenv("TEST_STDOUT") != null
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
