package jenavi.config

object OntologyProperties {
    val useTDB: Boolean
        get() = System.getenv("USE_TDB")?.toBooleanStrictOrNull() ?: true

    val inferenceEnabled: Boolean
        get() = System.getenv("JENA_INFERENCE")?.toBooleanStrictOrNull() ?: true

    val queryCacheEnabled: Boolean
        get() = System.getenv("QUERY_CACHE")?.toBooleanStrictOrNull() ?: true

    val reasonerType: String
        get() = (System.getenv("REASONER_TYPE") ?: "OWL_MINI").uppercase()

    val bindSchemaOnce: Boolean
        get() = System.getenv("REASONER_BIND_SCHEMA")?.toBooleanStrictOrNull() ?: true

    // 기존 값들 유지 (다른 코드에서 사용 중)
    val brokers: String
        get() = System.getenv("KAFKA_BROKERS") ?: "58.235.106.218:9092"
    val topic: String
        get() = System.getenv("KAFKA_TOPIC") ?: "ontology-rdf"
    val thresholdHours: Long
        get() = System.getenv("OBS_CLEAN_THRESHOLD_HOURS")?.toLongOrNull() ?: 12L
}