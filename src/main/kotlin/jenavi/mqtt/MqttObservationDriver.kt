package jenavi.mqtt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * FROST SensorThings MQTT 인입 드라이버 (Paho v3).
 *
 * FROST는 새 Observation 생성 시 구독 토픽(예: `v1.1/Observations`)으로
 * Observation 엔티티 JSON(thin)을 publish한다. 전체 센서 트리는 오지 않는다.
 * 이 드라이버는 thin 메시지를 [ObservationEvent]로 정규화해 [ObservationEventHandler]로 넘긴다.
 *
 * RDF/윈도우 로직은 여기 두지 않는다 — 인입(plumbing) 계층이다.
 */
@Component
class MqttObservationDriver(
    private val state: MqttConsumerState,
    private val handler: ObservationEventHandler,
    private val buffer: RecentObservationBuffer,
    private val mdsThingIndex: jenavi.frost.MdsThingIndex,
) {
    private val logger = LoggerFactory.getLogger(MqttObservationDriver::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()

    // "MultiDatastreams(19)" 우선 매칭, 단일 Datastream은 Multi가 아닌 경우만.
    private val mdsRegex = Regex("""MultiDatastreams\((\d+)\)""")
    private val dsRegex = Regex("""(?<!Multi)Datastreams\((\d+)\)""")

    @Volatile
    private var client: MqttClient? = null

    // 실제로 연결된 브로커 URI (모니터링 표시용; 정적 설정값이 아니라 현재 연결 대상)
    @Volatile
    private var activeBrokerUri: String? = null

    @Synchronized
    fun start(
        broker: String,
        topics: List<String>,
        clientId: String,
        qos: Int,
        username: String?,
        password: String?,
    ) {
        if (client?.isConnected == true) {
            logger.info("MQTT already connected: {}", broker)
            return
        }

        val c = MqttClient(broker, clientId, MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            username?.let { userName = it }
            password?.let { this.password = it.toCharArray() }
        }

        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                // 최초/재연결 모두에서 구독을 (재)설정
                topics.forEach { t ->
                    runCatching { c.subscribe(t, qos) }
                        .onSuccess { logger.info("MQTT subscribed (reconnect={}): {}", reconnect, t) }
                        .onFailure { logger.error("MQTT subscribe failed: {} ({})", t, it.message) }
                }
            }

            override fun connectionLost(cause: Throwable?) {
                logger.warn("MQTT connection lost: {}", cause?.message)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                if (!state.isActive()) return
                val payload = String(message.payload, Charsets.UTF_8)
                try {
                    val event = parse(topic, payload)
                    // FROST는 같은 토픽에서 (a) 센서의 원본 create 메시지(echo)와
                    // (b) 저장 후 정식 알림을 모두 흘린다. @iot.id가 있는 정식 알림만 채택해
                    // 중복·시각누락·로컬타임존 문제를 한 번에 제거한다.
                    if (event.observationId == null) {
                        state.incDropped()
                        return
                    }
                    val seq = state.incReceived()
                    buffer.record(event, seq, mdsThingIndex.nameOf(event.multiDatastreamId)) // 모니터링(항상)
                    handler.onObservation(event) // 처리(로깅/엔진, 교체 가능)
                } catch (e: Exception) {
                    logger.warn("MQTT message parse/handle failed on {}: {}", topic, e.message)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) { /* publish 안 함 */ }
        })

        c.connect(opts)
        client = c
        activeBrokerUri = broker
        state.activate()
        logger.info("MQTT connected: {} topics={}", broker, topics)
    }

    private fun parse(topic: String, payload: String): ObservationEvent {
        val node: JsonNode = mapper.readTree(payload)
        // MDS/DS id는 전달방식에 따라 위치가 다르다:
        //  (1) MqttExpand: 본문에 확장된 MultiDatastream 객체의 @iot.id
        //  (2) per-MDS 구독: 토픽 v1.1/MultiDatastreams(<id>)/Observations
        //  (3) nav 링크(있을 때) — 단, FROST는 Observations(<obsId>)/MultiDatastream 형태라
        //      MDS id가 안 들어있을 수 있어 신뢰도 낮음(후순위).
        val mdsNav = node.get("MultiDatastream@iot.navigationLink")?.asText()
        val dsNav = node.get("Datastream@iot.navigationLink")?.asText()
        val mdsId = node.path("MultiDatastream").path("@iot.id").asTextOrNull()
            ?: mdsRegex.find(topic)?.groupValues?.get(1)
            ?: mdsRegex.find(mdsNav ?: "")?.groupValues?.get(1)
        val dsId = node.path("Datastream").path("@iot.id").asTextOrNull()
            ?: dsRegex.find(topic)?.groupValues?.get(1)
            ?: dsRegex.find(dsNav ?: "")?.groupValues?.get(1)

        return ObservationEvent(
            observationId = node.get("@iot.id")?.asText(),
            multiDatastreamId = mdsId,
            datastreamId = dsId,
            phenomenonTime = node.get("phenomenonTime")?.asText(),
            resultTime = node.get("resultTime")?.asText(),
            result = normalizeResult(node.get("result")),
            topic = topic,
            rawPayload = payload,
        )
    }

    private fun normalizeResult(node: JsonNode?): List<String> = when {
        node == null || node.isNull -> emptyList()
        node.isArray -> node.map { it.asText() }
        else -> listOf(node.asText())
    }

    /** 누락/null/빈 노드면 null, 아니면 텍스트 값. */
    private fun JsonNode.asTextOrNull(): String? =
        if (isMissingNode || isNull) null else asText().takeIf { it.isNotBlank() }

    @Synchronized
    fun stop() {
        val c = client
        if (c != null) {
            runCatching { c.disconnect() }.onFailure { logger.warn("MQTT disconnect: {}", it.message) }
            runCatching { c.close() }
        }
        client = null
        activeBrokerUri = null
        state.deactivate()
        logger.info("MQTT stopped")
    }

    fun isRunning(): Boolean = client?.isConnected == true

    /** 현재 연결된 브로커 URI (미연결이면 null). */
    fun activeBroker(): String? = if (client?.isConnected == true) activeBrokerUri else null
}
