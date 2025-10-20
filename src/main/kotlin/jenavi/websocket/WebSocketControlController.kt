package jenavi.websocket

import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity

@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping("/ws")
class WebSocketControlController(
    private val serverState: WebSocketServerState,
    private val wsHandler: OntologyWebSocketHandler
) {

    @GetMapping("/start")
    fun start(): ResponseEntity<String> {
        serverState.activate()
        wsHandler.enable() // 연결 허용 설정
        return ResponseEntity.ok("WebSocket 서버 활성화됨")
    }

    @GetMapping("/stop")
    fun stop(): ResponseEntity<String> {
        serverState.deactivate()
        wsHandler.disable() // 기존 세션 닫고 연결 차단
        return ResponseEntity.ok("WebSocket 서버 비활성화됨")
    }

    @GetMapping("/status")
    fun status(): ResponseEntity<String> {
        val status = if (serverState.isActive()) "활성화됨" else "비활성화됨"
        return ResponseEntity.ok("WebSocket 서버 상태: $status")
    }
}
