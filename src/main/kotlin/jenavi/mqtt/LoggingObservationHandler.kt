package jenavi.mqtt

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 기본 핸들러: 수신 이벤트를 로깅/카운트만 한다.
 * 윈도우 엔진이 붙기 전까지 드라이버를 실제 FROST에 대해 검증하는 용도.
 */
@Component
class LoggingObservationHandler : ObservationEventHandler {

    private val logger = LoggerFactory.getLogger(LoggingObservationHandler::class.java)

    override fun onObservation(event: ObservationEvent) {
        logger.info(
            "MQTT obs id={} mds={} ds={} t={} result={}",
            event.observationId, event.multiDatastreamId, event.datastreamId,
            event.phenomenonTime, event.result
        )
    }
}
