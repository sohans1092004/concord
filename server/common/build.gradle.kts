plugins { `java-library` }

@Suppress("UNCHECKED_CAST")
val v = rootProject.extra["versions"] as Map<String, String>

dependencies {
    api("dev.concord:crdt-java:0.1.0")
    api("com.zaxxer:HikariCP:${v["hikari"]}")
    api("org.postgresql:postgresql:${v["postgres"]}")
    api("io.lettuce:lettuce-core:${v["lettuce"]}")
    api("com.auth0:java-jwt:${v["jwt"]}")
    api("org.slf4j:slf4j-api:${v["slf4j"]}")
    runtimeOnly("ch.qos.logback:logback-classic:${v["logback"]}")
}
