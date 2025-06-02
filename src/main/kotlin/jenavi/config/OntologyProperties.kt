package jenavi.config

object OntologyProperties {
    val useTDB: Boolean
        get() = System.getenv("USE_TDB")?.toBoolean() ?: true
}
