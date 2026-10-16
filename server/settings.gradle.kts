rootProject.name = "concord-server"

// The CRDT engine is its own build (crdt-java/); this pulls it in as dev.concord:crdt-java.
includeBuild("../crdt-java")

include("common", "session", "gateway", "devtools")
