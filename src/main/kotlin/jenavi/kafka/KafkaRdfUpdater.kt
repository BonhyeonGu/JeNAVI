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
    private val ont: OntModel,
    private val consumerState: KafkaConsumerState,
    private val updateCounter: KafkaUpdateCounter,
    private val webSocketHandler: OntologyWebSocketHandler
) {

    private val logger = LoggerFactory.getLogger(KafkaRdfUpdater::class.java)
    private var running = false
    private var thread: Thread? = null

    fun startConsumer(brokers: String, topic: String, useTDB: Boolean) {
        if (running) return
        running = true
        consumerState.activate()

        thread = thread(start = true) {
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
                val dataset = TDB2Factory.connectDataset("./_TDB")
                while (running) {
                    val records = consumer.poll(Duration.ofMillis(500))
                    if (!records.isEmpty) {
                        dataset.begin(ReadWrite.WRITE)
                        try {
                            val model = dataset.defaultModel
                            for (record in records) {
                                if (consumerState.isActive()) {
                                    val start = System.currentTimeMillis()

                                    record.value().byteInputStream().use { input ->
                                        model.read(input, null, "RDF/XML")
                                    }

                                    val elapsed = System.currentTimeMillis() - start

                                    val xml = record.value()
                                    val id = Regex("""STA_Plugin/([^/]+)/""").find(xml)?.groupValues?.get(1) ?: "unknown"
                                    val ts = Regex("""Observation/([0-9T:+\-]+)""").find(xml)?.groupValues?.get(1) ?: "unknown"
                                    val json = """{"id":"$id","timestamp":"$ts","readTimeMs":$elapsed}"""
                                    webSocketHandler.broadcast(json)
                                }
                            }
                            dataset.commit()
                        } finally {
                            dataset.end()
                        }
                    }
                }
                dataset.close()
            } else {
                while (running) {
                    val records = consumer.poll(Duration.ofMillis(500))
                    for (record in records) {
                        if (consumerState.isActive()) {
                            try {
                                val start = System.currentTimeMillis()

                                record.value().byteInputStream().use { input ->
                                    ont.read(input, null, "RDF/XML")
                                }

                                val elapsed = System.currentTimeMillis() - start

                                val xml = record.value()
                                val id = Regex("""STA_Plugin/([^/]+)/""").find(xml)?.groupValues?.get(1) ?: "unknown"
                                val ts = Regex("""Observation/([0-9T:+\-]+)""").find(xml)?.groupValues?.get(1) ?: "unknown"
                                val json = """{"id":"$id","timestamp":"$ts","readTimeMs":$elapsed}"""
                                webSocketHandler.broadcast(json)

                            } catch (e: Exception) {
                                logger.warn("메모리 모델 처리 중 오류", e)
                            }
                        }
                    }
                }
            }

            consumer.close()
            consumerState.deactivate()
        }
    }

    fun stopConsumer() {
        running = false
        thread?.join()
        thread = null
        consumerState.deactivate()
    }
}
