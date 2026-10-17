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
}
