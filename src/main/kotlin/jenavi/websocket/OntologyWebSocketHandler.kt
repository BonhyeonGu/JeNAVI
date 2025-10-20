package jenavi.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.socket.*
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import com.fasterxml.jackson.core.type.TypeReference
import java.nio.file.*
import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean

@Component
class OntologyWebSocketHandler : TextWebSocketHandler() {

    private val sessions = ConcurrentHashMap<String, WebSocketSession>()
    @Volatile private var acceptingConnections = true
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        if (acceptingConnections) {
            sessions[session.id] = session
        } else {
            session.close()
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessions.remove(session.id)
    }

    fun enable() {
        acceptingConnections = true
    }

    fun disable() {
        acceptingConnections = false
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    fun broadcast(originalJson: String) {
        val enrichedJson = try {
            val typeRef = object : TypeReference<MutableMap<String, Any>>() {}
            val data: MutableMap<String, Any> = objectMapper.readValue(originalJson, typeRef)

            data["sentAt"] = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            // 시스템 메모리 사용량 (free처럼)
            val osBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            val totalPhysicalMemory = osBean.totalPhysicalMemorySize
            val freePhysicalMemory = osBean.freePhysicalMemorySize
            val usedMemoryBytes = totalPhysicalMemory - freePhysicalMemory

            // 디렉토리 사용량
            val tdbPath = Paths.get("./_TDB").toAbsolutePath().normalize()
            val tdbSizeBytes = if (Files.exists(tdbPath)) {
                Files.walk(tdbPath)
                    .filter { Files.isRegularFile(it) }
                    .mapToLong { Files.size(it) }
                    .sum()
            } else 0L

            val ontState = mapOf(
                "usedMemoryBytes" to usedMemoryBytes,
                "tdbDirectorySizeBytes" to tdbSizeBytes
            )
            data["ontState"] = ontState

            objectMapper.writeValueAsString(data)
        } catch (e: Exception) {
            """{
                "message":${objectMapper.writeValueAsString(originalJson)},
                "sentAt":"${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}"
            }"""
        }

        sessions.values.forEach {
            if (it.isOpen) {
                try {
                    it.sendMessage(TextMessage(enrichedJson))
                } catch (_: Exception) {
                    // 무시
                }
            }
        }
    }
}
