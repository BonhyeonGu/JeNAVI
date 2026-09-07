package jenavi.frost

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * MultiDatastream id → Thing 이름 매핑.
 *
 * MQTT thin 메시지는 mds id만 주므로, 모니터링/표시에 Thing 이름이 필요하면 여기서 룩업한다.
 * MQTT 시작 시 FROST 열거 결과로 채워진다(정의 ABox 적재와 무관한 경량 인덱스).
 */
@Component
class MdsThingIndex {
    private val map = ConcurrentHashMap<String, String>()

    fun populate(refs: List<FrostClient.MdsRef>) {
        refs.forEach { r -> r.thingName?.let { map[r.id] = it } }
    }

    fun nameOf(mdsId: String?): String? = mdsId?.let { map[it] }

    fun size(): Int = map.size
}
