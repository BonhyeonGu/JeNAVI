package jenavi.mqtt

import org.springframework.stereotype.Component

/** 프론트엔드 모니터링용 최근 관측치 한 건. */
data class RecentObs(
    val seq: Long,
    val observationId: String?,
    val multiDatastreamId: String?,
    val thingName: String?,
    val phenomenonTime: String?,
    val result: List<String>,
    val receivedAtMs: Long,
)

/** /mqtt/recent 응답 페이로드. */
data class MqttMonitor(
    val running: Boolean,
    val received: Long,
    val dropped: Long,
    val broker: String,
    val recent: List<RecentObs>,
    val engine: jenavi.engine.WindowedReasoningEngine.EngineSnapshot? = null,
)

/**
 * 최근 채택된 관측치의 링버퍼(모니터링 전용).
 * 처리 핸들러(로깅/엔진)와 무관하게 드라이버가 항상 기록하므로,
 * 핸들러가 윈도우 엔진으로 교체돼도 모니터링은 유지된다.
 */
@Component
class RecentObservationBuffer {
    private val capacity = 100
    private val deque = ArrayDeque<RecentObs>(capacity)

    @Synchronized
    fun record(event: ObservationEvent, seq: Long, thingName: String?) {
        deque.addLast(
            RecentObs(
                seq = seq,
                observationId = event.observationId,
                multiDatastreamId = event.multiDatastreamId,
                thingName = thingName,
                phenomenonTime = event.phenomenonTime,
                result = event.result,
                receivedAtMs = System.currentTimeMillis(),
            )
        )
        while (deque.size > capacity) deque.removeFirst()
    }

    /** 최신순. */
    @Synchronized
    fun snapshot(): List<RecentObs> = deque.toList().asReversed()
}
