package jenavi

//--------------------------------------------------------------------
import org.slf4j.Logger
import org.slf4j.LoggerFactory
//--------------------------------------------------------------------
import org.apache.jena.ontology.OntModel
import org.apache.jena.ontology.OntModelSpec
import org.apache.jena.ontology.OntDocumentManager
//--------------------------------------------------------------------
import org.apache.jena.rdf.model.ModelFactory
//import org.apache.jena.tdb.TDBFactory // 온메모리를 하지 않을 때 고려되어야 함
//--------------------------------------------------------------------
import org.apache.jena.riot.RiotException
import org.apache.jena.vocabulary.RDF//통계에서
import org.apache.jena.rdf.model.RDFNode
//--------------------------------------------------------------------
import java.io.File // RDF 읽을 때 사용

class Ontology(val rule: OntModelSpec) {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(Ontology::class.java)
        const val PATH_DIR_RDF = "./_RDF"
        const val PATH_DIR_OWL = "./_OWL"
        val PATH_DIR_OWLS: Array<String> = arrayOf(
            "http://paper.9bon.org/ontologies/sensorthings/1.1.3",
            //"http://paper.9bon.org/ontologies/smartcity/0.2",
            "http://paper.9bon.org/ontologies/dtom/1.0"
        )
    }

    private val readStatusMap: MutableMap<String, Boolean> = mutableMapOf()

    //메니저 생성
    private val ontDocMgr = OntDocumentManager().apply {
        //setProcessImports(false)
        setReadFailureHandler { uri, model, e ->
            logger.error("Read Fail, URI => $uri, Handle => OntManager, ${e.message}")
            readStatusMap[uri] = false
        }
    }

    //온톨로지 모델의 메니저 및 룰 정의
    private val ontModelSpec = OntModelSpec(rule).apply {
        documentManager = ontDocMgr
    }
    
    val ontologyModel: OntModel = ModelFactory.createOntologyModel(ontModelSpec)

    init {
        // 작성한 OWL들을 불러옴
        PATH_DIR_OWLS.forEach { url ->
            logger.info("Try read URL => $url")
            readStatusMap[url] = true
            try {
                val file = File(url)
                val cleanFile = if (file.exists()) removeBOM(file) else file
    
                ontologyModel.read(cleanFile.toURI().toString(), "text/turtle")
                logger.info("Read Complete, Type => Turtle")
            } catch (e: Exception) {
                try {
                    ontologyModel.read(url, "application/rdf+xml")
                    logger.info("Read Complete, Type => RDF/XML")
                } catch (e: Exception) {
                    logger.error("Read Fail, URI => $url, Handle => OntManager, ${e.message}")
                    readStatusMap[url] = false
                }
            }
        }

        //!!!!OWL과 RDF를 읽는 방법이 다른지 추가적인 조사가 필요하다.!!!!
        readRDF(PATH_DIR_OWL)
        readRDF(PATH_DIR_RDF)
        calcStatistics()
        logger.info("")
        logger.info("")
        logger.info("Successfully read the following URLs without errors:")
        // 에러 없는 OWL, RDF 리스트
        readStatusMap.forEach { (url, success) ->
            if (success) {
                logger.info(url)
            }
        }
        logger.info("")
        logger.info("")
    }

    private fun readRDF(locale: String) {
        val directory = File(locale)
        if (directory.exists() && directory.isDirectory) {
            val files = directory.listFiles()
            files?.forEach { file ->
                logger.info("Read RDF => ${PATH_DIR_RDF}/${file.name}")
                try {
                    ontologyModel.read(file.absolutePath)
                } catch (e: RiotException) {
                    logger.error("RiotException => ${PATH_DIR_RDF}/${file.name}")
                }
            }
        } else {
            logger.error("The provided path is not a valid directory.")
        }
    }

    private fun calcStatistics() {
        val classUris = mutableSetOf<String>()
        val classWithInstances = mutableSetOf<String>()
        var totalInstances = 0

        val statements = ontologyModel.listStatements(null, RDF.type, null as RDFNode?)
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

        // 정의된 클래스 추출
        val definedClasses = ontologyModel.listClasses()
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
        val tempFile = File.createTempFile("cleaned_", ".ttl") // 임시 파일 생성
        val reader = file.inputStream().reader(Charsets.UTF_8)
        val content = reader.readText()
        reader.close()
    
        tempFile.writeText(content, Charsets.UTF_8) // BOM 제거 후 저장
        logger.info("BOM 제거 완료: ${file.name}")
        return tempFile
    }
}