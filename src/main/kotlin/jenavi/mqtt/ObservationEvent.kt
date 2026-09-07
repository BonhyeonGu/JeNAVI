package jenavi.mqtt

/**
 * FROST MQTT에서 수신한 단일 Observation 알림을 정규화한 이벤트.
 *
 * 인입 계층(MQTT 파싱)과 (향후) 윈도우 증분추론 엔진 사이의 경계 자료구조다.
 * 엔진은 RDF/FROST를 몰라야 하며, 이 이벤트만 소비한다.
 */
data class ObservationEvent(
    val observationId: String?,          // @iot.id
    val multiDatastreamId: String?,      // MultiDatastreams(<id>)
    val datastreamId: String?,           // Datastreams(<id>) (단일 datastream인 경우)
    val phenomenonTime: String?,         // event time
    val resultTime: String?,
    val result: List<String>,            // scalar는 단일 원소 리스트로 정규화
    val topic: String,
    val rawPayload: String,
)

/**
 * 인입 계층 → 엔진 경계.
 * 지금은 [LoggingObservationHandler]가 기본 구현이며,
 * 윈도우 엔진이 추가되면 그 엔진을 @Primary 빈으로 구현/주입한다.
 */
interface ObservationEventHandler {
    fun onObservation(event: ObservationEvent)
}
