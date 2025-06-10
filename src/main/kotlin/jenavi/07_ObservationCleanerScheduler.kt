package jenavi

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class ObservationCleanerScheduler(
    private val cleaner: ObservationCleaner
) {
    private val running = AtomicBoolean(false)

    fun start() {
        running.set(true)
    }

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    // 1시간마다 실행 (3600000ms)
    @Scheduled(fixedRate = 3600000)
    fun scheduledCleanup() {
        if (running.get()) {
            cleaner.cleanOutdatedObservations()
        }
    }
}
