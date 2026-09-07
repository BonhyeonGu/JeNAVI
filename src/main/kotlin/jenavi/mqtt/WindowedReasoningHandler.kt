package jenavi.mqtt

import jenavi.engine.WindowedReasoningEngine
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * @Primary ObservationEventHandler — 드라이버가 이걸 주입받는다.
 *
 * 인입 이벤트를 항상 [LoggingObservationHandler]로 흘려 로깅을 유지하고,
 * "반영"이 켜져 있으면 [WindowedReasoningEngine]으로도 라우팅한다.
 * (엔진 enable/disable은 /mqtt/apply 에서 토글; 엔진 내부에서 enabled를 확인)
 */
@Component
@Primary
class WindowedReasoningHandler(
    private val logging: LoggingObservationHandler,
    private val engine: WindowedReasoningEngine,
) : ObservationEventHandler {

    override fun onObservation(event: ObservationEvent) {
        logging.onObservation(event)
        engine.onEvent(event)
    }
}
