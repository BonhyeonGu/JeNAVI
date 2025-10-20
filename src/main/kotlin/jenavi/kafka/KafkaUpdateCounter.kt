package jenavi.kafka

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class KafkaUpdateCounter {
    private val counter = AtomicInteger(0)

    fun increment(): Int = counter.incrementAndGet()
    fun get(): Int = counter.get()
    fun reset() = counter.set(0)
}
