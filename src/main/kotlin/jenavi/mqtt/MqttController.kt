package jenavi.mqtt

import jenavi.config.OntologyProperties
import jenavi.engine.DefinitionLoader
import jenavi.engine.WindowedReasoningEngine
import jenavi.frost.FrostClient
import jenavi.frost.MdsThingIndex
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/mqtt")
@CrossOrigin(origins = ["*"])
class MqttController(
    private val driver: MqttObservationDriver,
    private val state: MqttConsumerState,
    private val frost: FrostClient,
    private val buffer: RecentObservationBuffer,
    private val mdsThingIndex: MdsThingIndex,
    private val engine: WindowedReasoningEngine,
    private val definitions: DefinitionLoader,
) {

    @GetMapping("/start")
    fun start(@RequestParam(defaultValue = "real") target: String): ResponseEntity<String> {
        // target=experiment: 별도 로컬 브로커(EXP_ 가짜 fleet 전용)에 직접 구독. FROST REST 열거는 건너뛴다.
        if (target == "experiment") {
            definitions.loadExperiment()   // 레지스트리(mds 1001..1023) + 정의 적재
            val expTopics = OntologyProperties.experimentMqttTopics
            driver.start(
                broker = OntologyProperties.experimentMqttBroker,
                topics = expTopics,
                clientId = OntologyProperties.mqttClientId,
                qos = OntologyProperties.mqttQos,
                username = OntologyProperties.mqttUsername,
                password = OntologyProperties.mqttPassword,
            )
            return ResponseEntity.ok(
                "MQTT ingest 시작됨 [실험]: ${OntologyProperties.experimentMqttBroker} (${expTopics.size} topics)"
            )
        }
        // 실제 레인: FROST에서 MDS 컴포넌트를 읽어 레지스트리(실제 mds id -> op/uom) + 정의 적재.
        // 이게 있어야 엔진이 실제 MDS를 resolve해 Observation을 붙인다.
        definitions.loadReal()
        // autoDiscover=true면 REST로 MDS를 열거해 per-MDS 토픽 구독(MDS id가 토픽에 포함됨).
        // 실패하거나 비어 있으면 설정된 토픽으로 폴백.
        val topics = if (OntologyProperties.mqttAutoDiscover) {
            // 한 번의 열거로 (a) per-MDS 토픽 생성 + (b) mds→Thing 이름 인덱스 채우기
            val mdsList = frost.listMultiDatastreams()
            mdsThingIndex.populate(mdsList)
            mdsList.map { "v1.1/MultiDatastreams(${it.id})/Observations" }
                .ifEmpty { OntologyProperties.mqttTopics }
        } else {
            OntologyProperties.mqttTopics
        }
        driver.start(
            broker = OntologyProperties.mqttBroker,
            topics = topics,
            clientId = OntologyProperties.mqttClientId,
            qos = OntologyProperties.mqttQos,
            username = OntologyProperties.mqttUsername,
            password = OntologyProperties.mqttPassword,
        )
        return ResponseEntity.ok(
            "MQTT ingest 시작됨: ${OntologyProperties.mqttBroker} (${topics.size} topics)"
        )
    }

    @GetMapping("/stop")
    fun stop(): ResponseEntity<String> {
        driver.stop()
        return ResponseEntity.ok("MQTT ingest 중지됨")
    }

    /** "반영" 버튼: 윈도우 증분추론 엔진을 켜고/끈다(온톨로지에 IndexPoint 집계 반영). */
    @GetMapping("/apply")
    fun apply(@RequestParam(defaultValue = "true") on: Boolean): ResponseEntity<String> {
        if (on) engine.enable() else engine.disable()
        return ResponseEntity.ok(
            if (on) "온톨로지 반영 시작됨 (윈도우 엔진 ON)" else "온톨로지 반영 중지됨 (OFF)"
        )
    }

    /** 집계 산출 방식 선택: indexed(IndexPoint) | naive(전체 스캔) | both(비교). */
    @GetMapping("/mode")
    fun mode(@RequestParam value: String): ResponseEntity<String> {
        engine.setMode(value)
        return ResponseEntity.ok("집계 산출 모드: ${value}")
    }

    @GetMapping("/status")
    fun status(): ResponseEntity<String> {
        val s = if (state.isActive() && driver.isRunning()) "활성화됨" else "비활성화됨"
        return ResponseEntity.ok("MQTT 상태: $s, 채택 ${state.count()}건 / 폐기(원본echo) ${state.droppedCount()}건")
    }

    /** 프론트엔드 모니터링용 JSON (실행상태 + 카운터 + 최근 관측치). */
    @GetMapping("/recent")
    fun recent(): ResponseEntity<MqttMonitor> =
        ResponseEntity.ok(
            MqttMonitor(
                running = state.isActive() && driver.isRunning(),
                received = state.count(),
                dropped = state.droppedCount(),
                broker = driver.activeBroker() ?: "(중지됨)",
                recent = buffer.snapshot(),
                engine = engine.snapshot(),
            )
        )
}
