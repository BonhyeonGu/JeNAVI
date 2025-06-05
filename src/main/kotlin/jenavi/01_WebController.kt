package jenavi
//--------------------------------------------------------------------
import org.apache.jena.ontology.OntModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
//--------------------------------------------------------------------
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody // 문자열을 렌더링 없이 간단한 방법으로 출력
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.http.ResponseEntity
//--------------------------------------------------------------------
import org.apache.jena.ontology.OntModelSpec // RULE
import org.apache.jena.riot.RiotException // Exc
//--------------------------------------------------------------------
import java.time.LocalDateTime // 시간
import java.time.format.DateTimeFormatter
import java.io.FileOutputStream // 파일 입출력
//--------------------------------------------------------------------
import java.io.File
//--------------------------------------------------------------------
import org.w3c.dom.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import jenavi.config.OntologyProperties
import java.io.FileInputStream

import org.apache.jena.tdb2.TDB2Factory

data class ApiResponse<T>(
    val status: String,
    val executionTimeMs: Long,
    val data: T?
)

data class SparqlRequest(
    val query: String
)

// ./gradlew bootRun
@Controller
class WebController(
    private var ont: OntModel
) : AutoCloseable {

    private val logger: Logger = LoggerFactory.getLogger(WebController::class.java)
    private var ontQ: OntQuery = OntQuery(ont, cache = false)

    override fun close() {
        println("WebController closed.")
    }

    @GetMapping("/")
    fun index(model: Model): String {
        logger.debug("User Request /")
        model.addAttribute("message", "Index")
        return "index"
    }

    @GetMapping("/init")
    fun initOntology(model: Model): String {
        logger.info("User Request /init : Reinitializing ontology")
        val useTDB = OntologyProperties.useTDB
        val newOntology = Ontology.createOntology(useTDB)
        ont = newOntology.ontologyModel
        ontQ = OntQuery(ont, cache = false)
        model.addAttribute("message", "Ontology reloaded with cache disabled.")
        return "index"
    }

    //Validation
    @GetMapping("/test")
    fun test(model: Model): String {
        logger.debug("User Request /test")
        val vali = Validate(ont)
        //jenaValidate.validationTest_OWL("https://paper.9bon.org/ontologies/sensorthings/1.1")
        vali.validationTest_OWLandRDF()
        model.addAttribute("message", "Finish : test")
        return "index"
    }


    //Browser=========================================================================================================
    @GetMapping("/browse/{resource}")
    fun browseResource(@PathVariable resource: String, model: Model): String {
        logger.debug("User Request /browse/$resource")

        val useTDB = OntologyProperties.useTDB
        val resourceURI = ontQ.deShort(resource)
        model.addAttribute("resourceURI", resourceURI)

        val isProperty = if (useTDB) {
            val dataset = TDB2Factory.connectDataset("./_TDB")
            ontQ.isProperty(resourceURI, dataset)
        } else {
            ontQ.isProperty(resourceURI)
        }

        return if (isProperty) {
            val q = """
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

            SELECT ?property ?value WHERE {
                {
                    <$resourceURI> rdfs:domain ?domainClass.
                    ?domainClass owl:unionOf ?classList.
                    ?classList rdf:rest*/rdf:first ?value.
                    BIND (rdfs:domain AS ?property)
                }
                UNION
                {
                    <$resourceURI> rdfs:range ?value.
                    BIND (rdfs:range AS ?property)
                }
                UNION
                {
                    <$resourceURI> owl:restriction ?value.
                    BIND (owl:restriction AS ?property)
                }
            }
        """.trimIndent()

            val startTime = System.currentTimeMillis()
            val propertyDetails = if (useTDB) {
                val dataset = TDB2Factory.connectDataset("./_TDB")
                ontQ.browseQuery(q, dataset)
            } else {
                ontQ.browseQuery(q)
            }
            val endTime = System.currentTimeMillis()

            model.addAttribute("executionTime", endTime - startTime)
            model.addAttribute("propertyDetails", propertyDetails)
            "browseProperty"
        } else {
            // 🔥 일반 리소스 탐색 처리
            val q1 = """
            SELECT ?property ?value WHERE {
                <$resourceURI> ?property ?value.
            }   
        """.trimIndent()
            val startTime0 = System.currentTimeMillis()
            val resourceInfo = if (useTDB) {
                val dataset = TDB2Factory.connectDataset("./_TDB")
                ontQ.browseQuery(q1, dataset)
            } else {
                ontQ.browseQuery(q1)
            }
            val endTime0 = System.currentTimeMillis()

            val q2 = """
            SELECT ?property ?value WHERE {
                ?value ?property <$resourceURI>.
            }
        """.trimIndent()
            val startTime1 = System.currentTimeMillis()
            val resourceInfoReverse = if (useTDB) {
                val dataset = TDB2Factory.connectDataset("./_TDB")
                ontQ.browseQuery(q2, dataset)
            } else {
                ontQ.browseQuery(q2)
            }
            val endTime1 = System.currentTimeMillis()

            model.addAttribute("executionTime0", endTime0 - startTime0)
            model.addAttribute("resourceInfo", resourceInfo)
            model.addAttribute("executionTime1", endTime1 - startTime1)
            model.addAttribute("resourceInfoReverse", resourceInfoReverse)

            "browse"
        }
    }


    @GetMapping("/queryForm")
    fun queryForm(): String {
        return "queryForm"
    }

    @PostMapping("/executeQuery")
    fun executeQuery(@RequestParam sparqlQuery: String, model: Model): String {
        val resultsList = ontQ.executeSPARQL(sparqlQuery)
        model.addAttribute("results", resultsList)
        return "queryResults"
    }

    //API로 질의문 받았을 때 좀 문제가 발생하는 듯
    fun normalizeQuery(query: String): String {
        return query
            .lines()
            .map { it.replace(Regex("[\\uFEFF\\u00A0\\u3000\\r\\t]"), "").trimStart() }
            .joinToString("\n")
            .trim()
    }


    @ResponseBody
    @PostMapping("/queryRun")
    fun queryRun(@RequestBody request: SparqlRequest): ResponseEntity<ApiResponse<MutableList<List<String>>>> {
        logger.info("User Request /queryRun")
        //logger.info(request.toString())

        val useTDB = OntologyProperties.useTDB
        val (executionTime, result) = if (useTDB) {
            val dataset = TDB2Factory.connectDataset("./_TDB")
            ontQ.qRun(normalizeQuery(request.query), dataset)
        } else {
            ontQ.qRun(normalizeQuery(request.query))
        }

        val status = if (result.isEmpty()) "ok (no result or update)" else "ok"
        val response = ApiResponse(status, executionTime, result)
        //logger.info(response.toString())
        return ResponseEntity.ok(response)
    }



//=====================================================================================================

    //All Save
    @GetMapping("/save")
    @ResponseBody
    fun save(): ApiResponse<Any?> {
        val startTime = System.currentTimeMillis()

        val currentDateTime = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        val filename = "./" + currentDateTime.format(formatter) + ".rdf"

        FileOutputStream(filename).use { outStream ->
            ont.write(outStream, "RDF/XML")
        }

        fixRdfStringLiterals(filename)

        val executionTime = System.currentTimeMillis() - startTime

        return ApiResponse(
            status = "success",
            executionTimeMs = executionTime,
            data = null // 지금은 비워두기
        )
    }


    @GetMapping("/dump")
    @ResponseBody
    fun dump(): ApiResponse<String> {
        val startTime = System.currentTimeMillis()

        // 1. 파일명 생성
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        val filename = "./" + LocalDateTime.now().format(formatter) + ".rdf"
        val file = File(filename)

        try {
            // 2. RDF/XML 저장
            FileOutputStream(file).use { outStream ->
                ont.write(outStream, "RDF/XML")
            }

            // 3. 후처리
            fixRdfStringLiterals(filename)

            // 4. 내용 읽기
            val rdfContent = file.readText(Charsets.UTF_8)

            val executionTime = System.currentTimeMillis() - startTime

            // 5. 응답 반환
            return ApiResponse(
                status = "success",
                executionTimeMs = executionTime,
                data = rdfContent
            )
        } catch (e: Exception) {
            return ApiResponse(
                status = "error",
                executionTimeMs = System.currentTimeMillis() - startTime,
                data = "Error: ${e.message}"
            )
        } finally {
            // 6. 파일 삭제
            if (file.exists()) {
                file.delete()
            }
        }
    }


    // 제나는 리터럴 타입 스트링을 빼버림, 자기가 다시 읽을때는 상관이 없는데 protege 에서는 이해를 못함
    fun fixRdfStringLiterals(filePath: String) {
        val inputFile = File(filePath)
        if (!inputFile.exists()) {
            println("File not found: $filePath")
            return
        }
    
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputFile)
    
        val rdfNS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        val xsdStringURI = "http://www.w3.org/2001/XMLSchema#string"
    
        val allElements = doc.getElementsByTagName("*")
        for (i in 0 until allElements.length) {
            val elem = allElements.item(i) as Element
    
            // 조건: 단일 텍스트 노드를 가진 요소이며, 이미 datatype이나 xml:lang이 없음
            if (elem.childNodes.length == 1 &&
                elem.firstChild.nodeType == Node.TEXT_NODE &&
                !elem.hasAttributeNS(rdfNS, "datatype") &&
                !elem.hasAttribute("xml:lang")) {
    
                val textContent = elem.textContent?.trim()
                if (!textContent.isNullOrEmpty()) {
                    // rdf:datatype 추가
                    elem.setAttributeNS(rdfNS, "rdf:datatype", xsdStringURI)
                }
            }
        }
    
        // 저장 (덮어쓰기)
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.transform(DOMSource(doc), StreamResult(inputFile))
    
        println("✔ Fixed and saved RDF/XML file: $filePath")
    }

//=====================================================================================================

    //RDF Update and Delete

    // 적재된 것을 일괄적으로 적용함
    @GetMapping("/ReadyToUpdate")
    @ResponseBody
    fun readyToUpdate(model: Model): ResponseEntity<ApiResponse<Any>> {
        logger.info("User Request /ReadyToUpdate")
        val pDir = "./_TUN_UpdateReady"
        val directory = File(pDir)

        return if (directory.exists() && directory.isDirectory) {
            val files = directory.listFiles()
            var successCount = 0
            var failureCount = 0
            var totalParsingTime: Long = 0

            files?.forEach { file ->
                try {
                    val inputStream = FileInputStream(file)
                    inputStream.use {
                        val parseStart = System.currentTimeMillis()
                        ont.read(it, null, "RDF/XML")
                        val parseEnd = System.currentTimeMillis()
                        totalParsingTime += (parseEnd - parseStart)
                    }
                    successCount++
                } catch (e: RiotException) {
                    logger.error("RiotException => ${pDir}/${file.name}")
                    failureCount++
                } catch (e: Exception) {
                    logger.error("Unexpected exception => ${e.message}")
                    failureCount++
                }
            }

            // RDF 읽기 이후 삭제
            files?.forEach { file ->
                file.delete()
            }

            logger.info("ont.read parsing only time: $totalParsingTime ms")

            ResponseEntity.ok(
                ApiResponse(
                    status = "ok",
                    executionTimeMs = totalParsingTime,
                    data = mapOf(
                        "successCount" to successCount,
                        "failureCount" to failureCount
                    )
                )
            )
        } else {
            logger.error("The provided path is not a valid directory.")
            ResponseEntity.badRequest().body(
                ApiResponse(
                    status = "error",
                    executionTimeMs = 0,
                    data = mapOf("error" to "Invalid directory")
                )
            )
        }
    }

    // 정의 적용
    @GetMapping("/DefToUpdate")
    @ResponseBody
    fun defToUpdate(model: Model): Map<String, Any> {
        logger.info("User Request /DefToUpdate")
        val pDir = "./_TUN_SensorDefReady"
        val directory = File(pDir)

        if (directory.exists() && directory.isDirectory) {
            val files = directory.listFiles()
            val startTime = System.currentTimeMillis() // 시간 측정 시작
            var successCount = 0
            var failureCount = 0

            files?.forEach { file ->
                logger.info("Read RDF => ${pDir}/${file.name}")
                try {
                    ont.read(file.absolutePath)
                    print(file.absolutePath)
                    successCount++
                    file.delete()
                    // 파일 삭제
                    //if (file.delete()) {
                    //    logger.info("Successfully deleted: ${file.name}")
                    //} else {
                    //    logger.warn("Failed to delete: ${file.name}")
                    //}
                } catch (e: RiotException) {
                    logger.error("RiotException => ${pDir}/${file.name}")
                    failureCount++
                }
            }
            val endTime = System.currentTimeMillis()

            logger.info("ont.read completed in ${endTime - startTime} ms")
            return mapOf(
                "resultTime" to (endTime - startTime),
                "successCount" to successCount,
                "failureCount" to failureCount
            )
        } else {
            logger.error("The provided path is not a valid directory.")
            return mapOf(
                "error" to "Invalid directory"
            )
        }
    }


//=====================================================================================================

    @GetMapping("/debugUpdate/{pName}")
    fun debugUpdate(@PathVariable pName: String, model: Model): String {
        logger.info("User Request /debugUpdate/${pName}")
        val executionTime = ontQ.debugUpdateRand(pName)

        model.addAttribute("message", "Execution time: $executionTime ms")
        return "index"
    }


}