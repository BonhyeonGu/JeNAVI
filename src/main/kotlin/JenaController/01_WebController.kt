package JenaController
//--------------------------------------------------------------------
import org.slf4j.Logger
import org.slf4j.LoggerFactory
//--------------------------------------------------------------------
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody // 문자열을 렌더링 없이 간단한 방법으로 출력

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController // JSON&XML
//--------------------------------------------------------------------
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.ResultSet
import org.apache.jena.query.ResultSetFormatter
import org.apache.jena.ontology.OntModelSpec // RULE
import org.apache.jena.riot.RiotException // Exc
//--------------------------------------------------------------------
import JenaController.Ontology
import JenaController.Validate
import JenaController.OntQuery
//--------------------------------------------------------------------
import java.time.LocalDateTime // 시간
import java.time.format.DateTimeFormatter
import java.io.FileOutputStream // 파일 입출력
import org.json.JSONObject // JSON 객체
import java.io.ByteArrayOutputStream // JSON 변환
//--------------------------------------------------------------------
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
//--------------------------------------------------------------------
import org.w3c.dom.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

// ./gradlew bootRun 
@Controller
class WebController : AutoCloseable {
    companion object {
        val RULE = OntModelSpec.OWL_MEM_TRANS_INF
        private val logger: Logger = LoggerFactory.getLogger(WebController::class.java)
        val ont = Ontology(rule = RULE).ontologyModel
        val ontQ = OntQuery(ont, cache = false)
    }

    data class ApiResponse<T>(
        val status: String,
        val executionTimeMs: Long,
        val data: T?
    )

    override fun close() {
        // 필요하다면 리소스 정리 코드 작성
        println("WebController closed.")
    }

    @GetMapping("/")
    fun index(model: Model): String {
        logger.debug("User Request /")
        model.addAttribute("message", "Index")
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

        var startTime: Long = 0
        var endTime: Long = 0
        var executionTime: Long = 0

        val resourceURI = ontQ.deShort(resource)
        model.addAttribute("resourceURI", resourceURI)
        if (ontQ.isProperty(resourceURI)) {
            startTime = System.currentTimeMillis()
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
            val propertyDetails = ontQ.browseQuery(q)
            endTime = System.currentTimeMillis()
            executionTime = endTime - startTime
            model.addAttribute("executionTime", executionTime)
            model.addAttribute("propertyDetails", propertyDetails)
            return "browseProperty"
        } else {
            startTime = System.currentTimeMillis()
            var q = """
                SELECT ?property ?value WHERE {
                    <$resourceURI> ?property ?value.
                }   
            """.trimIndent()
            var resourceInfo = ontQ.browseQuery(q)
            endTime = System.currentTimeMillis()
            val executionTime0 = endTime - startTime
            model.addAttribute("executionTime0", executionTime0)
            model.addAttribute("resourceInfo", resourceInfo)

            startTime = System.currentTimeMillis()
            q = """
                SELECT ?property ?value WHERE {
                    ?value ?property <$resourceURI>.
                }
            """.trimIndent()
            
            resourceInfo = ontQ.browseQuery(q)
            endTime = System.currentTimeMillis()
            val executionTime1 = endTime - startTime
            model.addAttribute("executionTime1", executionTime1)
            model.addAttribute("resourceInfoReverse", resourceInfo)

            return "browse"
        }
    }


    //Query Update
    @GetMapping("/reloadQuery")
    fun reloadQuery(model: Model): String {
        logger.info("User Request /reloadQuery")
        ontQ.reloadQuery()
        model.addAttribute("message", "Finish : reloadQuery")
        return "index"
    }


    //
    @GetMapping("/queryForm")
    fun showQueryForm(): String {
        return "queryForm"
    }


    @PostMapping("/executeQuery")
    fun executeQuery(@RequestParam sparqlQuery: String, model: Model): String {
        val resultsList = ontQ.executeSPARQL(sparqlQuery)
        model.addAttribute("results", resultsList)
        return "queryResults"
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
            WebController.ont.write(outStream, "RDF/XML")
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
                WebController.ont.write(outStream, "RDF/XML")
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
    fun readyToUpdate(model: Model): Map<String, Any> {
        logger.info("User Request /ReadyToUpdate")
        val pDir = "./_TUN_UpdateReady"
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
                    successCount++

                    // 파일 삭제
                    if (file.delete()) {
                        logger.info("Successfully deleted: ${file.name}")
                    } else {
                        logger.warn("Failed to delete: ${file.name}")
                    }
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
                    successCount++

                    // 파일 삭제
                    if (file.delete()) {
                        logger.info("Successfully deleted: ${file.name}")
                    } else {
                        logger.warn("Failed to delete: ${file.name}")
                    }
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

    // 옵저베이션 삭제, 구현중
    @GetMapping("/DeleteObservation/{n}")
    @ResponseBody
    fun deleteObservation(@PathVariable n: Int): Map<String, Any> {
        logger.info("User Request /DeleteObservation/$n")
        val et = ontQ.deleteObservationAllThings(n)
        return mapOf(
            "et" to et
        )
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