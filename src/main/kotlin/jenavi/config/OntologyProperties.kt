package jenavi.config

object OntologyProperties {
    val useTDB: Boolean
        get() = System.getenv("USE_TDB")?.toBoolean() ?: false
    val brokers: String
        get() = System.getenv("KAFKA_BROKERS") ?: "58.235.106.218:9092"
    val topic: String
        get() = System.getenv("KAFKA_TOPIC") ?: "ontology-rdf"
    val thresholdHours: Long
        get() = System.getenv("OBS_CLEAN_THRESHOLD_HOURS")?.toLongOrNull() ?: 12L
}