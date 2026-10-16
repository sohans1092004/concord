plugins {
    java
    application
    jacoco
}

group = "dev.concord"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

application { mainClass = "dev.concord.crdt.cli.Main" }

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("concord.vectors", project.findProperty("vectors") ?: "${rootDir}/../conformance/vectors")
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}
