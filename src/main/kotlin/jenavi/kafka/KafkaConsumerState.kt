package jenavi.kafka

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class KafkaConsumerState {
    private val active = AtomicBoolean(false)

    fun activate() = active.set(true)
    fun deactivate() = active.set(false)
    fun isActive(): Boolean = active.get()
}
