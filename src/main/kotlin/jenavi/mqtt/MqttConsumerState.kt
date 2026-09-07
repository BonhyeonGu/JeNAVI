package jenavi.mqtt

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Component
class MqttConsumerState {
    private val active = AtomicBoolean(false)
    private val received = AtomicLong(0)
    private val dropped = AtomicLong(0)

    fun activate() = active.set(true)
    fun deactivate() = active.set(false)
    fun isActive(): Boolean = active.get()

    fun incReceived(): Long = received.incrementAndGet()
    fun count(): Long = received.get()

    // 원본 create echo 등 @iot.id 없는 비정식 메시지를 버린 횟수
    fun incDropped(): Long = dropped.incrementAndGet()
    fun droppedCount(): Long = dropped.get()
}
