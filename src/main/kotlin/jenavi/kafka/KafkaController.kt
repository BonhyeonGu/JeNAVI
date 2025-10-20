package jenavi.kafka

import jenavi.KafkaRdfUpdater
import jenavi.config.OntologyProperties
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/kafka")
@CrossOrigin(origins = ["*"])
class UpdateController(
    private val kafkaRdfUpdater: KafkaRdfUpdater,
    private val kafkaState: KafkaConsumerState
) {

    @GetMapping("/start")
    fun startKafka(): ResponseEntity<String> {
        val brokers = OntologyProperties.brokers
        val topic = OntologyProperties.topic
        val useTDB = OntologyProperties.useTDB

        kafkaRdfUpdater.startConsumer(brokers, topic, useTDB)
        return ResponseEntity.ok("Kafka RDF 업데이트 시작됨")
    }

    @GetMapping("/stop")
    fun stopKafka(): ResponseEntity<String> {
        kafkaRdfUpdater.stopConsumer()
        return ResponseEntity.ok("Kafka RDF 업데이트 중지됨")
    }

    @GetMapping("/status")
    fun kafkaStatus(): ResponseEntity<String> {
        val status = if (kafkaState.isActive()) "활성화됨" else "비활성화됨"
        return ResponseEntity.ok("Kafka Consumer 상태: $status")
    }
}