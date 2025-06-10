package jenavi

import org.apache.jena.query.ReadWrite
import org.apache.jena.riot.RiotException
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.ontology.OntModel
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.*
import kotlin.concurrent.thread

@Component
class KafkaRdfUpdater(private val ont: OntModel) {

    private val logger = LoggerFactory.getLogger(KafkaRdfUpdater::class.java)

    private var running = false
    private var thread: Thread? = null

    fun startConsumer(brokers: String, topic: String, useTDB: Boolean) {
        if (running) return
        running = true

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
                            records.forEach { record ->
                                record.value().byteInputStream().use { input ->
                                    model.read(input, null, "RDF/XML")
                                }
                            }
                            dataset.commit()
                        } catch (e: Exception) {
                            logger.error("TDB Consumer error", e)
                        } finally {
                            dataset.end()
                        }
                    }
                }
                dataset.close()
            } else {
                while (running) {
                    val records = consumer.poll(Duration.ofMillis(500))
                    records.forEach { record ->
                        try {
                            record.value().byteInputStream().use { input ->
                                ont.read(input, null, "RDF/XML")
                            }
                        } catch (e: RiotException) {
                            logger.warn("Invalid RDF received", e)
                        } catch (e: Exception) {
                            logger.error("Memory model consumer error", e)
                        }
                    }
                }
            }

            consumer.close()
        }
    }

    fun stopConsumer() {
        running = false
        thread?.join()
        thread = null
    }
}
