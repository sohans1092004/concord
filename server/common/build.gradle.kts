plugins { `java-library` }

@Suppress("UNCHECKED_CAST")
val v = rootProject.extra["versions"] as Map<String, String>

dependencies {
    api("dev.concord:crdt-java:0.1.0")
    api("com.zaxxer:HikariCP:${v["hikari"]}")
    api("org.postgresql:postgresql:${v["postgres"]}")
    api("io.lettuce:lettuce-core:${v["lettuce"]}")
    api("org.bouncycastle:bcprov-jdk18on:${v["bouncycastle"]}") // argon2id in pure Java (Passwords)
    api("org.slf4j:slf4j-api:${v["slf4j"]}")
    api("io.javalin:javalin:${v["javalin"]}") // Jetty WebSocket sessions, for Outbox
    api("ch.qos.logback:logback-classic:${v["logback"]}") // RedactingJsonEncoder
    // Tracing (SPEC 9.5): the API everywhere; the SDK and an OTLP exporter over the JDK's own
    // HTTP client (no OkHttp). Off unless CONCORD_OTLP_ENDPOINT is set (never on the trial VM).
    api("io.opentelemetry:opentelemetry-api:${v["otel"]}")
    implementation("io.opentelemetry:opentelemetry-sdk:${v["otel"]}")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:${v["otel"]}") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation("io.opentelemetry:opentelemetry-exporter-sender-jdk:${v["otel"]}")
}
