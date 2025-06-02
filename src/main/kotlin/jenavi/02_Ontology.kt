package jenavi

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.jena.ontology.OntModel
import org.apache.jena.ontology.OntModelSpec
import org.apache.jena.ontology.OntDocumentManager
import org.apache.jena.query.Dataset
import org.apache.jena.query.ReadWrite
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.RiotException
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.vocabulary.RDF
import org.apache.jena.rdf.model.RDFNode
import java.io.File

class Ontology(private val model: OntModel) {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(Ontology::class.java)
        const val PATH_DIR_RDF = "./_RDF"
        const val PATH_DIR_OWL = "./_OWL"
        val PATH_DIR_OWLS: Array<String> = arrayOf(
            "http://paper.9bon.org/ontologies/sensorthings/1.1.3",
            //"http://paper.9bon.org/ontologies/smartcity/0.2",
            "http://paper.9bon.org/ontologies/dtom/1.0"
        )

        fun createOntology(useTDB: Boolean): Ontology {
            val ontDocMgr = OntDocumentManager().apply {
                setReadFailureHandler { uri, _, e ->
                    logger.error("Read Fail, URI => $uri, ${e.message}")
                }
            }

            val ontModelSpec = OntModelSpec(OntModelSpec.OWL_MEM_TRANS_INF).apply {
                documentManager = ontDocMgr
            }

            if (useTDB) {
                val dataset: Dataset = TDB2Factory.connectDataset("./_TDB")
                var ontology: Ontology

                // WRITE 트랜잭션에서 온톨로지 초기화
                dataset.begin(ReadWrite.WRITE)
                try {
                    val tdbModel = ModelFactory.createOntologyModel(ontModelSpec, dataset.defaultModel)
                    ontology = Ontology(tdbModel)
                    ontology.loadOntologies() // 여기서 트랜잭션 안에서 read() 실행
                    dataset.commit()
                } finally {
                    dataset.end()
                }

                // READ 모드로 실제 서비스에서 사용할 모델 열기
                dataset.begin(ReadWrite.READ)
                try {
                    val tdbModel = ModelFactory.createOntologyModel(ontModelSpec, dataset.defaultModel)
                    ontology = Ontology(tdbModel)
                } finally {
                    dataset.end()
                }

                return ontology
            } else {
                val memModel = ModelFactory.createOntologyModel(ontModelSpec)
                val ontology = Ontology(memModel)
                ontology.loadOntologies() // 온메모리라면 트랜잭션 필요 없음
                return ontology
            }
        }
    }

    private val readStatusMap: MutableMap<String, Boolean> = mutableMapOf()

    val ontologyModel: OntModel
        get() = model

    // 🟢 이제는 외부에서 트랜잭션으로 감싸서 호출되도록 한다
    fun loadOntologies() {
        PATH_DIR_OWLS.forEach { url ->
            logger.info("Try read URL => $url")
            readStatusMap[url] = true
            try {
                val file = File(url)
                val cleanFile = if (file.exists()) removeBOM(file) else file
                model.read(cleanFile.toURI().toString(), "text/turtle")
                logger.info("Read Complete, Type => Turtle")
            } catch (e: Exception) {
                try {
                    model.read(url, "application/rdf+xml")
                    logger.info("Read Complete, Type => RDF/XML")
                } catch (e: Exception) {
                    logger.error("Read Fail, URI => $url, ${e.message}")
                    readStatusMap[url] = false
                }
            }
        }

        readRDF(PATH_DIR_OWL)
        readRDF(PATH_DIR_RDF)
        calcStatistics()

        logger.info("")
        logger.info("Successfully read the following URLs without errors:")
        readStatusMap.forEach { (url, success) ->
            if (success) {
                logger.info(url)
            }
        }
        logger.info("")
    }

    private fun readRDF(locale: String) {
        val directory = File(locale)
        if (directory.exists() && directory.isDirectory) {
            val files = directory.listFiles()
            files?.forEach { file ->
                logger.info("Read RDF => ${file.absolutePath}")
                try {
                    model.read(file.absolutePath)
                } catch (e: RiotException) {
                    logger.error("RiotException => ${file.name}")
                }
            }
        } else {
            logger.error("The provided path is not a valid directory: $locale")
        }
    }

    private fun calcStatistics() {
        val classUris = mutableSetOf<String>()
        val classWithInstances = mutableSetOf<String>()
        var totalInstances = 0

        val statements = model.listStatements(null, RDF.type, null as RDFNode?)
        while (statements.hasNext()) {
            val stmt = statements.nextStatement()
            val obj = stmt.`object`
            if (obj.isResource) {
                val classUri = obj.asResource().uri
                if (classUri != null) {
                    classUris.add(classUri)
                    classWithInstances.add(classUri)
                    totalInstances++
                }
            }
        }

        val definedClasses = model.listClasses()
            .toList()
            .mapNotNull { it.uri }
            .toSet()

        val totalClassCount = definedClasses.size
        val classWithInstanceCount = definedClasses.intersect(classWithInstances).size

        val classRichness = if (totalClassCount > 0) classWithInstanceCount.toDouble() / totalClassCount else 0.0
        val averagePopulation = if (totalClassCount > 0) totalInstances.toDouble() / totalClassCount else 0.0

        logger.info("📊 온톨로지 통계 요약:")
        logger.info("전체 클래스 수 (정의된 owl:Class): $totalClassCount")
        logger.info("인스턴스가 존재하는 클래스 수: $classWithInstanceCount")
        logger.info("전체 인스턴스 수 (rdf:type 포함): $totalInstances")
        logger.info("Class Richness (CR): %.3f".format(classRichness))
        logger.info("Average Population (AP): %.3f".format(averagePopulation))
    }

    private fun removeBOM(file: File): File {
        val tempFile = File.createTempFile("cleaned_", ".ttl")
        val reader = file.inputStream().reader(Charsets.UTF_8)
        val content = reader.readText()
        reader.close()

        tempFile.writeText(content, Charsets.UTF_8)
        logger.info("BOM 제거 완료: ${file.name}")
        return tempFile
    }
}
