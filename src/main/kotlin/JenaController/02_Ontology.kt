package JenaController

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
import org.apache.jena.reasoner.ValidityReport
//--------------------------------------------------------------------
import org.apache.jena.vocabulary.RDFS
import org.apache.jena.riot.RiotException
//--------------------------------------------------------------------
import java.io.File // RDF 읽을 때 사용

class Ontology(val rule: OntModelSpec) {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(Ontology::class.java)
        const val TDB_LOCALE = "./_TDB"
        const val RDF_LOCALE = "./_RDF"
        const val OWL_LOCALE = "./_OWL"
        val OWL_LOCALES: Array<String> = arrayOf(
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
        OWL_LOCALES.forEach { url ->
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
        readRDF(OWL_LOCALE)
        readRDF(RDF_LOCALE)
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
                logger.info("Read RDF => ${RDF_LOCALE}/${file.name}")
                try {
                    ontologyModel.read(file.absolutePath)
                } catch (e: RiotException) {
                    logger.error("RiotException => ${RDF_LOCALE}/${file.name}")
                }
            }
        } else {
            logger.error("The provided path is not a valid directory.")
        }
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