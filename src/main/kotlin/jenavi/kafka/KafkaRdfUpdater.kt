package jenavi

import jenavi.kafka.KafkaConsumerState
import jenavi.kafka.KafkaUpdateCounter
import jenavi.websocket.OntologyWebSocketHandler
import org.apache.jena.query.ReadWrite
import org.apache.jena.ontology.OntModel
import org.apache.jena.riot.RiotException
import org.apache.jena.tdb2.TDB2Factory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.*
import kotlin.concurrent.thread

@Component
class KafkaRdfUpdater(
    private val ontology: Ontology,                 // ⬅ OntModel 대신 Ontology 주입
    private val consumerState: KafkaConsumerState,
    private val updateCounter: KafkaUpdateCounter,
    private val webSocketHandler: OntologyWebSocketHandler
) {

    private val logger = LoggerFactory.getLogger(KafkaRdfUpdater::class.java)
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    // 살짝 성능 위해 정규식은 미리 준비
    private val idRegex = Regex("""STA_Plugin/([^/]+)/""")
    private val tsRegex = Regex("""Observation/([0-9T:+\-]+)""")

    /**
     * @param useTDB 외부에서 강제 지정(기존 시그니처 유지). 보통은 OntologyProperties.useTDB와 동일.
     */
    fun startConsumer(brokers: String, topic: String, useTDB: Boolean) {
        if (running) return
        running = true
        consumerState.activate()

        thread = thread(start = true, name = "KafkaRdfUpdater") {
            val props = Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "OntologyUpdate")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
            }

            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(topic))

            if (useTDB) {
                // --- TDB 모드: 직접 Dataset에 WRITE 트랜잭션으로 반영 ---
                val dataset = TDB2Factory.connectDataset(Ontology.PATH_DIR_TDB)
                try {
                    while (running) {
                        val records = consumer.poll(Duration.ofMillis(500))
                        if (records.isEmpty) continue

                        dataset.begin(ReadWrite.WRITE)
                        try {
                            val model = dataset.defaultModel
                            for (record in records) {
                                if (!consumerState.isActive()) continue
                                val xml = record.value()

                                val start = System.currentTimeMillis()
                                xml.byteInputStream().use { input ->
                                    model.read(input, null, "RDF/XML")
                                }
                                val elapsed = System.currentTimeMillis() - start

                                // 브로드캐스트 + 카운터
                                val id = idRegex.find(xml)?.groupValues?.get(1) ?: "unknown"
                                val ts = tsRegex.find(xml)?.groupValues?.get(1) ?: "unknown"
                                val json = """{"id":"$id","timestamp":"$ts","readTimeMs":$elapsed}"""
                                webSocketHandler.broadcast(json)
                                runCatching { updateCounter.increment() }
                            }
                            dataset.commit()
                        } catch (e: Exception) {
                            logger.warn("TDB update failed, rolling back", e)
                            runCatching { dataset.abort() }
                        } finally {
                            dataset.end()
                        }
                    }
                } finally {
                    runCatching { dataset.close() }
                }
            } else {
                // --- In-Memory 모드: 매 poll 시점에 "현재" 모델을 가져다 씀 ---
                while (running) {
                    val records = consumer.poll(Duration.ofMillis(500))
                    if (records.isEmpty) continue

                    // ⬇ 매번 최신 OntModel (재초기화 후에도 최신 참조)
                    val model: OntModel = ontology.ontologyModel

                    for (record in records) {
                        if (!consumerState.isActive()) continue
                        val xml = record.value()

                        try {
                            val start = System.currentTimeMillis()
                            xml.byteInputStream().use { input ->
                                model.read(input, null, "RDF/XML")
                            }
                            val elapsed = System.currentTimeMillis() - start

                            val id = idRegex.find(xml)?.groupValues?.get(1) ?: "unknown"
                            val ts = tsRegex.find(xml)?.groupValues?.get(1) ?: "unknown"
                            val json = """{"id":"$id","timestamp":"$ts","readTimeMs":$elapsed}"""
                            webSocketHandler.broadcast(json)
                            runCatching { updateCounter.increment() }
                        } catch (e: RiotException) {
                            logger.warn("메모리 모델 RDF/XML 파싱 오류", e)
                        } catch (e: Exception) {
                            logger.warn("메모리 모델 처리 중 오류", e)
                        }
                    }
                }
            }

            runCatching { consumer.close() }
            consumerState.deactivate()
        }
    }

    fun stopConsumer() {
        running = false
        // 간단히 join — 필요 시 consumer.wakeup() 패턴으로 개선 가능
        thread?.join()
        thread = null
        consumerState.deactivate()
    }
}
