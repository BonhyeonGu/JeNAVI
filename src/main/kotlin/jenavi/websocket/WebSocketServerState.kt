package jenavi.websocket

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class WebSocketServerState {
    private val active = AtomicBoolean(false)

    fun isActive(): Boolean = active.get()
    fun activate() = active.set(true)
    fun deactivate() = active.set(false)
}
