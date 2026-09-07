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

    // --- FROST MQTT ingest ---
    // 주: serverSettings는 mqtt://...:1883을 광고하지만 이는 내부값. 외부 노출 포트는 60500.
    val mqttBroker: String
        get() = System.getenv("MQTT_BROKER") ?: "tcp://aidtlab.com:60500"
    // FROST REST 베이스 (MDS 열거 → per-MDS 구독 토픽 생성에 사용)
    val frostRestBase: String
        get() = System.getenv("FROST_REST_BASE") ?: "http://aidtlab.com:60501/FROST-Server/v1.1"
    // true면 REST로 MultiDatastream을 열거해 per-MDS 토픽을 구독(권장: MDS id가 토픽에 포함됨)
    val mqttAutoDiscover: Boolean
        get() = System.getenv("MQTT_AUTO_DISCOVER")?.toBoolean() ?: true
    // 콤마로 여러 토픽 지정(autoDiscover=false일 때 사용). 기본: 모든 Observation 생성 알림.
    val mqttTopics: List<String>
        get() = (System.getenv("MQTT_TOPICS") ?: "v1.1/Observations")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val mqttClientId: String
        get() = System.getenv("MQTT_CLIENT_ID") ?: "jenavi-ingest"
    val mqttUsername: String?
        get() = System.getenv("MQTT_USERNAME")?.takeIf { it.isNotBlank() }
    val mqttPassword: String?
        get() = System.getenv("MQTT_PASSWORD")?.takeIf { it.isNotBlank() }
    val mqttQos: Int
        get() = System.getenv("MQTT_QOS")?.toIntOrNull() ?: 1

    // --- 실험 레인 (직접 구동한 로컬 브로커 + EXP_ 가짜 fleet) ---
    // /mqtt/start?target=experiment 로 런타임 선택. (실제 데이터 오염 방지: 실험은 항상 별도
    // 브로커에서 — aidtlab은 읽기전용 참조)
    val experimentMqttBroker: String
        get() = System.getenv("EXPERIMENT_MQTT_BROKER") ?: "tcp://localhost:1883"
    // 실험 브로커엔 EXP_ fleet만 있으므로 와일드카드로 모든 per-MDS Observation 토픽을 구독.
    val experimentMqttTopics: List<String>
        get() = (System.getenv("EXPERIMENT_MQTT_TOPICS") ?: "v1.1/+/Observations")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
}