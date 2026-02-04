package jenavi
//--------------------------------------------------------------------
import jenavi.config.OntologyProperties
import jenavi.contracts.*
import org.springframework.http.MediaType
//--------------------------------------------------------------------
import org.slf4j.Logger
import org.slf4j.LoggerFactory
//--------------------------------------------------------------------
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
//--------------------------------------------------------------------
import org.apache.jena.riot.RiotException // Exc
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.query.Dataset
//--------------------------------------------------------------------
import java.io.File
import java.io.FileInputStream
//--------------------------------------------------------------------
import java.lang.management.ManagementFactory

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = ["*"])
class WebController(private val ontology: Ontology) {
    private val logger: Logger = LoggerFactory.getLogger(WebController::class.java)

    @Volatile
    private var ontQ: OntQuery = OntQuery(ontology, cache = true)

    init {
        // 서버 기동 시 1회 자동 로드
        try {
            logger.info("Startup: loadDefaultOntologies from ./_OWL + ./_RDF ...")
            val sum = ontology.loadDefaultOntologies(followImports = true)
            ontQ = OntQuery(ontology, cache = true)

            logger.info(
                "Startup load done: tried=${sum.totalTried}, loaded=${sum.readAuto}, failed=${sum.failed}, elapsedMs=${sum.elapsedMs}"
            )
        } catch (e: Exception) {
            logger.error("Startup ontology loading failed", e)
            throw e
        }
    }

    // --- Health ---
    @GetMapping("/ping")
    fun ping(): ResponseEntity<ApiResponse<String>> =
        ResponseEntity.ok(ApiResponse("ok", 0, "pong"))


    // --- status ---
    private fun computeOntologyStatsViaSparql(): OntologyStats {
        val q = """
        PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX owl:  <http://www.w3.org/2002/07/owl#>
        SELECT ?totalClassCount ?classWithInstanceCount ?totalInstances WHERE {
          { SELECT (COUNT(DISTINCT ?cls) AS ?totalClassCount) WHERE {
              {
                { ?cls a owl:Class } UNION { ?cls a rdfs:Class } UNION
                { ?cls rdfs:subClassOf ?x } UNION { ?x rdfs:subClassOf ?cls } UNION
                { ?s rdf:type ?cls }
              }
              FILTER (isIRI(?cls) && ?cls NOT IN (owl:Thing, owl:Nothing))
          } }
          { SELECT (COUNT(DISTINCT ?c) AS ?classWithInstanceCount) WHERE {
              ?s rdf:type ?c .
              FILTER (isIRI(?c) && ?c NOT IN (owl:Thing, owl:Nothing))
          } }
          { SELECT (COUNT(*) AS ?totalInstances) WHERE {
              ?s rdf:type ?c .
              FILTER (isIRI(?c))
          } }
        }
    """.trimIndent()

        val timed = ontQ.runSparql(q)  // ← Dataset 없이 호출 (OntModel 경로)

        val table = timed.result as? QueryResult.Table
            ?: return OntologyStats(0,0,0,0.0,0.0)

        if (table.rows.isEmpty()) return OntologyStats(0,0,0,0.0,0.0)

        fun idxOf(v: String) = table.vars.indexOf(v).takeIf { it >= 0 } ?: -1
        val iTotal   = idxOf("totalClassCount")
        val iWithIns = idxOf("classWithInstanceCount")
        val iInst    = idxOf("totalInstances")

        val r = table.rows[0]
        fun asInt(i: Int) = r.getOrNull(i)?.trim()?.toIntOrNull() ?: 0

        val totalClassCount = asInt(iTotal)
        val classWithInstanceCount = asInt(iWithIns)
        val totalInstances = asInt(iInst)

        val classRichness =
            if (totalClassCount > 0) classWithInstanceCount.toDouble() / totalClassCount else 0.0
        val averagePopulation =
            if (totalClassCount > 0) totalInstances.toDouble() / totalClassCount else 0.0

        return OntologyStats(
            totalClassCount = totalClassCount,
            classWithInstanceCount = classWithInstanceCount,
            totalInstances = totalInstances,
            classRichness = classRichness,
            averagePopulation = averagePopulation
        )
    }

    @GetMapping("/status", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun status(): ResponseEntity<ApiResponse<StatusDataDto>> {
        val start = System.nanoTime()

        val tdbBytes: Long = if (OntologyProperties.useTDB) {
            runCatching { ontology.tdbDiskUsageBytes() }.getOrDefault(0L)
        } else 0L

        val uptimeMs: Long = ManagementFactory.getRuntimeMXBean().uptime

        val rt = Runtime.getRuntime()
        val heapCommitted: Long = rt.totalMemory()
        val heapMax: Long       = rt.maxMemory()
        val heapUsed: Long      = heapCommitted - rt.freeMemory()

        val stats = computeOntologyStatsViaSparql()

        val payload = StatusDataDto(
            storage = if (OntologyProperties.useTDB) "TDB2" else "in-memory",
            tdbBytes = tdbBytes,
            uptimeMs = uptimeMs,
            heapUsedBytes = heapUsed,
            heapCommittedBytes = heapCommitted,
            heapMaxBytes = heapMax,
            ontologyStats = OntologyStats(
                totalClassCount = stats.totalClassCount,
                classWithInstanceCount = stats.classWithInstanceCount,
                totalInstances = stats.totalInstances,
                classRichness = stats.classRichness,
                averagePopulation = stats.averagePopulation
            )
        )

        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        return ResponseEntity.ok(
            ApiResponse(
                status = "ok",
                timeMs = elapsedMs,
                data = payload
            )
        )
    }


    // ------


    // 온톨로지 초기화, 그런데 ontQ를 새로 만드는 것은 시간에 포함하지 않음
    @GetMapping("/init")
    fun init(): ResponseEntity<ApiResponse<Map<String, String>>> {
        logger.info("User Request GET /api/init : FULL reset")
        return try {
            val elapsed = ontology.fullResetAndReload()
            ontQ = OntQuery(ontology, cache = true)
            ResponseEntity.ok(
                ApiResponse(
                    status = "ok",
                    timeMs = elapsed,
                    data = mapOf(
                        "message" to "ontology fully reset & reloaded",
                        "storage" to (if (OntologyProperties.useTDB) "TDB2" else "in-memory"),
                        "elapsedMs" to elapsed.toString()
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("Failed full reset", e)
            ResponseEntity.status(500).body(
                ApiResponse(
                    status = "error",
                    timeMs = 0,
                    data = mapOf("error" to (e.message ?: "unknown"))
                )
            )
        }
    }


    @PostMapping("/query")
    fun query(@RequestBody request: SparqlRequest): ResponseEntity<ApiResponse<Any>> {
        val query = request.query.trim()
        //logger.info("POST /api/queryRun : ${query.replace('\n', ' ')}")
        logger.info("POST /api/queryRun")
        return try {
            val timed: TimedResult = ontQ.runSparql(query)

            val body: ApiResponse<Any> = when (val r = timed.result) {
                is QueryResult.Table -> ApiResponse("ok", timed.millis, TableDTO(r.vars, r.rows))
                is QueryResult.Bool  -> ApiResponse("ok", timed.millis, BoolDTO(r.value))
                is QueryResult.Graph -> {
                    val fmt = when ((request.graphFormat ?: "TURTLE").uppercase()) {
                        "TURTLE", "TTL"      -> RDFFormat.TURTLE_PRETTY
                        "NTRIPLES", "NT"     -> RDFFormat.NTRIPLES_UTF8
                        "JSONLD", "JSON-LD"  -> RDFFormat.JSONLD_PRETTY
                        "RDFXML", "RDF/XML", "XML" -> RDFFormat.RDFXML_PRETTY
                        else                 -> RDFFormat.TURTLE_PRETTY
                    }
                    val rdf = java.io.ByteArrayOutputStream().also {
                        RDFDataMgr.write(it, r.model, fmt)
                    }.toString(Charsets.UTF_8)
                    ApiResponse("ok", timed.millis, GraphDTO(rdf, fmt.lang.label))
                }
                is QueryResult.UpdateAck -> ApiResponse("ok (update)", timed.millis, UpdateAckDTO)
            }

            ResponseEntity.ok(body)

        } catch (e: IllegalArgumentException) {
            logger.warn("Bad SPARQL request", e)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse<Any>("error", 0, mapOf("message" to (e.message ?: "Bad request")))
            )
        } catch (e: Exception) {
            logger.error("queryRun error", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse<Any>("error", 0, mapOf("message" to "Internal server error"))
            )
        }
    }

    @PostMapping("/browse")
    fun browse(@RequestBody req: BrowseRequest): ResponseEntity<ApiResponse<BrowsePayload>> {
        logger.info("User Request POST /api/browse")

        val resourceURI = req.uri
        val useTDB = OntologyProperties.useTDB

        // 1) Property 여부 판정
        val isProperty: Boolean = if (useTDB) {
            var ds: Dataset? = null
            try {
                ds = TDB2Factory.connectDataset("./_TDB")
                ontQ.isProperty(resourceURI, ds)
            } finally {
                try {
                    ds?.close()
                } catch (_: Exception) {
                }
            }
        } else {
            ontQ.isProperty(resourceURI)
        }

        if (isProperty) {
            // --- 프로퍼티 브라우징 ---
            val q = """
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl:  <http://www.w3.org/2002/07/owl#>
            PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?property ?value WHERE {
              {
                <$resourceURI> rdfs:domain ?domainClass .
                OPTIONAL {
                  ?domainClass owl:unionOf ?classList .
                  ?classList rdf:rest*/rdf:first ?value
                }
                BIND (rdfs:domain AS ?property)
                FILTER(BOUND(?value) || !BOUND(?classList))
              }
              UNION
              {
                <$resourceURI> rdfs:range ?value .
                BIND (rdfs:range AS ?property)
              }
              UNION
              {
                ?r a owl:Restriction ;
                   owl:onProperty <$resourceURI> .
                VALUES ?k {
                  owl:someValuesFrom owl:allValuesFrom owl:hasValue
                  owl:minCardinality owl:maxCardinality owl:cardinality
                  owl|minQualifiedCardinality owl|maxQualifiedCardinality owl:onClass
                }
                ?r ?k ?value .
                BIND (?k AS ?property)
              }
            }
        """.trimIndent()

            val t0 = System.currentTimeMillis()
            val rows: List<Array<String>> = if (useTDB) {
                var ds: Dataset? = null
                try {
                    ds = TDB2Factory.connectDataset("./_TDB")
                    ontQ.browseQuery(q, ds)   // List<Array<String>>
                } finally {
                    try {
                        ds?.close()
                    } catch (_: Exception) {
                    }
                }
            } else {
                ontQ.browseQuery(q)
            }
            val dt = System.currentTimeMillis() - t0

            val mapped: List<TripleRow> = rows.map { arr ->
                TripleRow(
                    property = arr.getOrNull(0) ?: "",
                    value = arr.getOrNull(1) ?: "",
                    link = arr.getOrNull(2)?.takeUnless { it == "x" },
                    rowspan = arr.getOrNull(3)?.toIntOrNull() ?: 1
                )
            }

            val payload = BrowsePayload(
                resourceURI = resourceURI,
                isProperty = true,
                propertyDetails = mapped,
                outgoing = null,
                incoming = null,
                timePropertyMs = dt,
                timeOutgoingMs = null,
                timeIncomingMs = null
            )
            return ResponseEntity.ok(ApiResponse(status = "ok", timeMs = dt, data = payload))
        }

        // 2) 일반 리소스: outgoing / incoming
        val qOutgoing = """
        SELECT ?property ?value WHERE {
            <$resourceURI> ?property ?value .
        }
    """.trimIndent()
        val tOut0 = System.currentTimeMillis()
        val outRows: List<Array<String>> = if (useTDB) {
            var ds: Dataset? = null
            try {
                ds = TDB2Factory.connectDataset("./_TDB")
                ontQ.browseQuery(qOutgoing, ds)
            } finally {
                try {
                    ds?.close()
                } catch (_: Exception) {
                }
            }
        } else {
            ontQ.browseQuery(qOutgoing)
        }
        val tOut = System.currentTimeMillis() - tOut0

        val qIncoming = """
        SELECT ?property ?value WHERE {
            ?value ?property <$resourceURI> .
        }
    """.trimIndent()
        val tIn0 = System.currentTimeMillis()
        val inRows: List<Array<String>> = if (useTDB) {
            var ds: Dataset? = null
            try {
                ds = TDB2Factory.connectDataset("./_TDB")
                ontQ.browseQuery(qIncoming, ds)
            } finally {
                try {
                    ds?.close()
                } catch (_: Exception) {
                }
            }
        } else {
            ontQ.browseQuery(qIncoming)
        }
        val tIn = System.currentTimeMillis() - tIn0

        val outgoing = outRows.map { arr ->
            TripleRow(
                property = arr.getOrNull(0) ?: "",
                value = arr.getOrNull(1) ?: "",
                link = arr.getOrNull(2)?.takeUnless { it == "x" },
                rowspan = arr.getOrNull(3)?.toIntOrNull() ?: 1
            )
        }
        val incoming = inRows.map { arr ->
            TripleRow(
                property = arr.getOrNull(0) ?: "",
                value = arr.getOrNull(1) ?: "",
                link = arr.getOrNull(2)?.takeUnless { it == "x" },
                rowspan = arr.getOrNull(3)?.toIntOrNull() ?: 1
            )
        }

        val payload = BrowsePayload(
            resourceURI = resourceURI,
            isProperty = false,
            propertyDetails = null,
            outgoing = outgoing,
            incoming = incoming,
            timePropertyMs = null,
            timeOutgoingMs = tOut,
            timeIncomingMs = tIn
        )
        val total = tOut + tIn
        return ResponseEntity.ok(ApiResponse(status = "ok", timeMs = total, data = payload))
    }


    @GetMapping("/dump")
    fun dump(): ResponseEntity<ApiResponse<String>> {
        val t0 = System.currentTimeMillis()

        // 1) 파일명/확장자 결정: TDB면 .trig(데이터셋 전체), 아니면 .rdf(모델)
        val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
        val useTdb = OntologyProperties.useTDB
        val ext = if (useTdb) ".trig" else ".rdf"
        val filename = "./dump_$ts$ext"
        val file = java.io.File(filename)

        return try {
            // 2) 저장 (트랜잭션/스냅샷은 saveOntology 내부에서 처리)
            ontology.saveOntology(filename, includeNamedGraphs = useTdb)

            // 3) (선택) 기존에 사용하던 후처리가 있다면 RDF/XML일 때만 호출
            // if (!useTdb) runCatching { fixRdfStringLiterals(filename) }

            // 4) 파일 내용 읽어서 반환
            val content = file.readText(Charsets.UTF_8)
            val dt = System.currentTimeMillis() - t0
            ResponseEntity.ok(
                ApiResponse(
                    status = "success",
                    timeMs = dt,
                    data = content
                )
            )
        } catch (e: Exception) {
            logger.error("dump error", e)
            val dt = System.currentTimeMillis() - t0
            ResponseEntity.status(500).body(
                ApiResponse(
                    status = "error",
                    timeMs = dt,
                    data = "Error: ${e.message}"
                )
            )
        } finally {
            // 5) 임시 파일 정리
            runCatching { if (file.exists()) file.delete() }
        }
    }


    // 제나는 리터럴 타입 스트링을 빼버림, 자기가 다시 읽을때는 상관이 없는데 protege 는 이해를 못함
    private fun fixRdfStringLiterals(filePath: String) {
        val inputFile = java.io.File(filePath)
        if (!inputFile.exists()) {
            logger.warn("File not found: $filePath")
            return
        }

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputFile)

        val rdfNS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        val xsdStringURI = "http://www.w3.org/2001/XMLSchema#string"

        val allElements = doc.getElementsByTagName("*")
        for (i in 0 until allElements.length) {
            val elem = allElements.item(i) as org.w3c.dom.Element

            // 조건: 단일 텍스트 노드를 가진 요소이며, 이미 datatype이나 xml:lang이 없음
            if (elem.childNodes.length == 1 &&
                elem.firstChild.nodeType == org.w3c.dom.Node.TEXT_NODE &&
                !elem.hasAttributeNS(rdfNS, "datatype") &&
                !elem.hasAttribute("xml:lang")
            ) {

                val textContent = elem.textContent?.trim()
                if (!textContent.isNullOrEmpty()) {
                    elem.setAttributeNS(rdfNS, "rdf:datatype", xsdStringURI)
                }
            }
        }

        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.transform(
            javax.xml.transform.dom.DOMSource(doc),
            javax.xml.transform.stream.StreamResult(inputFile)
        )

        logger.info("✔ Fixed and saved RDF/XML file: $filePath")
    }


    @CrossOrigin(origins = ["*"])
    @PostMapping("/ingest/updates")
    fun ingestUpdates(@RequestBody req: IngestRequest): ResponseEntity<ApiResponse<Map<String, Any>>> {
        logger.info("User Request POST /api/ingest/updates")

        val dirPath = req.dir ?: "./_TUN_UpdateReady"
        val format = req.format ?: "RDF/XML"
        val deleteAfter = req.deleteAfter

        val directory = File(dirPath)
        if (!directory.exists() || !directory.isDirectory) {
            logger.error("Invalid directory: $dirPath")
            return ResponseEntity.badRequest().body(
                ApiResponse(status = "error", timeMs = 0, data = mapOf("error" to "Invalid directory"))
            )
        }

        val files = directory.listFiles()
        var successCount = 0
        var failureCount = 0
        var totalParsingTime = 0L

        files?.forEach { file ->
            try {
                FileInputStream(file).use { input ->
                    val t0 = System.currentTimeMillis()
                    ontology.writeTx { m -> m.read(input, null, format) }
                    totalParsingTime += (System.currentTimeMillis() - t0)
                }
                successCount++
                if (deleteAfter) runCatching { file.delete() }
            } catch (e: RiotException) {
                logger.error("RiotException while reading $dirPath/${file.name}")
                failureCount++
            } catch (e: Exception) {
                logger.error("Unexpected exception => ${e.message}")
                failureCount++
            }
        }

        logger.info("ingestUpdates parsing time: ${totalParsingTime} ms")

        return ResponseEntity.ok(
            ApiResponse(
                status = "ok",
                timeMs = totalParsingTime,
                data = mapOf(
                    "dir" to dirPath,
                    "format" to format,
                    "deleteAfter" to deleteAfter,
                    "successCount" to successCount,
                    "failureCount" to failureCount
                )
            )
        )
    }


    @CrossOrigin(origins = ["*"])
    @PostMapping("/ingest/definitions")
    fun ingestDefinitions(@RequestBody req: IngestRequest): ResponseEntity<ApiResponse<Map<String, Any>>> {
        logger.info("User Request POST /api/ingest/definitions")

        val dirPath = req.dir ?: "./_TUN_SensorDefReady"
        val format = req.format ?: "RDF/XML"  // ont.read(filePath)도 가능하지만 통일
        val deleteAfter = req.deleteAfter

        val directory = File(dirPath)
        if (!directory.exists() || !directory.isDirectory) {
            logger.error("Invalid directory: $dirPath")
            return ResponseEntity.badRequest().body(
                ApiResponse(status = "error", timeMs = 0, data = mapOf("error" to "Invalid directory"))
            )
        }

        val files = directory.listFiles()
        val t0 = System.currentTimeMillis()
        var successCount = 0
        var failureCount = 0

        files?.forEach { file ->
            logger.info("Read RDF => $dirPath/${file.name}")
            try {
                // 파일 경로로 바로 읽거나, 스트림으로 읽거나 둘 중 택1
                FileInputStream(file).use { input ->
                    ontology.writeTx { m -> m.read(input, null, format) }
                }
                successCount++
                if (deleteAfter) runCatching { file.delete() }
            } catch (e: RiotException) {
                logger.error("RiotException => $dirPath/${file.name}")
                failureCount++
            } catch (e: Exception) {
                logger.error("Unexpected exception => ${e.message}")
                failureCount++
            }
        }
        val dt = System.currentTimeMillis() - t0
        logger.info("ingestDefinitions completed in ${dt} ms")

        return ResponseEntity.ok(
            ApiResponse(
                status = "ok",
                timeMs = dt,
                data = mapOf(
                    "dir" to dirPath,
                    "format" to format,
                    "deleteAfter" to deleteAfter,
                    "successCount" to successCount,
                    "failureCount" to failureCount
                )
            )
        )
    }


    @CrossOrigin(origins = ["*"])
    @PostMapping("/ingest/dir")
    fun ingestFromDir(
        @RequestParam("dir") dir: String, @RequestParam("deleteAfter", defaultValue = "true") deleteAfter: Boolean,
        @RequestParam("format", defaultValue = "RDF/XML") format: String
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        logger.info("User Request POST /api/ingest/dir dir=$dir deleteAfter=$deleteAfter format=$format")

        val directory = java.io.File(dir)
        if (!directory.exists() || !directory.isDirectory) {
            logger.error("Invalid directory: $dir")
            return ResponseEntity.badRequest().body(
                jenavi.contracts.ApiResponse(status = "error", timeMs = 0, data = mapOf("error" to "Invalid directory"))
            )
        }

        val files = directory.listFiles()
        var successCount = 0
        var failureCount = 0
        var totalParsingTime = 0L

        val useTDB = OntologyProperties.useTDB

        if (useTDB) {
            var dataset: org.apache.jena.query.Dataset? = null
            try {
                dataset = org.apache.jena.tdb2.TDB2Factory.connectDataset("./_TDB")
                dataset.begin(org.apache.jena.query.ReadWrite.WRITE)
                try {
                    val model = dataset.defaultModel
                    files?.forEach { file ->
                        if (!file.isFile) return@forEach
                        try {
                            java.io.FileInputStream(file).use { inputStream ->
                                val t0 = System.currentTimeMillis()
                                model.read(inputStream, null, format)
                                totalParsingTime += (System.currentTimeMillis() - t0)
                            }
                            successCount++
                        } catch (e: org.apache.jena.riot.RiotException) {
                            logger.error("RiotException => $dir/${file.name}")
                            failureCount++
                        } catch (e: Exception) {
                            logger.error("Unexpected exception => ${e.message}")
                            failureCount++
                        }
                    }
                    dataset.commit()
                } catch (e: Exception) {
                    logger.error("Transaction failed: ${e.message}")
                    dataset.abort()
                    failureCount = files?.size ?: 0
                } finally {
                    dataset.end()
                }
            } finally {
                try {
                    dataset?.close()
                } catch (_: Exception) {
                }
            }
        } else {
            // 온메모리 모델
            files?.forEach { file ->
                if (!file.isFile) return@forEach
                try {
                    java.io.FileInputStream(file).use { inputStream ->
                        val t0 = System.currentTimeMillis()
                        ontology.writeTx { m -> m.read(inputStream, null, format) }
                        totalParsingTime += (System.currentTimeMillis() - t0)
                    }
                    successCount++
                } catch (e: org.apache.jena.riot.RiotException) {
                    logger.error("RiotException => $dir/${file.name}")
                    failureCount++
                } catch (e: Exception) {
                    logger.error("Unexpected exception => ${e.message}")
                    failureCount++
                }
            }
        }

        if (deleteAfter) {
            files?.forEach { f -> runCatching { if (f.isFile) f.delete() } }
            logger.info("Files deleted after RDF loading.")
        }

        logger.info("ingestFromDir parsing time: $totalParsingTime ms")

        return ResponseEntity.ok(
            jenavi.contracts.ApiResponse(
                status = "ok",
                timeMs = totalParsingTime,
                data = mapOf(
                    "dir" to dir,
                    "format" to format,
                    "deleteAfter" to deleteAfter,
                    "successCount" to successCount,
                    "failureCount" to failureCount,
                    "totalFiles" to (files?.count { it.isFile } ?: 0)
                )
            )
        )
    }


    @CrossOrigin(origins = ["*"])
    @PostMapping("/upload/rdf", consumes = ["multipart/form-data"])
    fun uploadRdf(
        @RequestParam("files") files: List<org.springframework.web.multipart.MultipartFile>,
        @RequestParam("format", defaultValue = "RDF/XML") format: String
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        logger.info("User Request POST /api/upload/rdf")

        fun detectLang(name: String, fallback: String): String {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".ttl")                      -> "TURTLE"
                lower.endsWith(".rdf") ||
                        lower.endsWith(".owl") ||
                        lower.endsWith(".xml")                      -> "RDF/XML"
                lower.endsWith(".nt")                       -> "N-TRIPLES"
                lower.endsWith(".nq")                       -> "NQUADS"
                lower.endsWith(".trig")                     -> "TRIG"
                lower.endsWith(".jsonld")                   -> "JSON-LD"
                else                                        -> fallback
            }
        }

        var successCount = 0
        var failureCount = 0
        var totalParsingTime = 0L

        files.forEach { file ->
            val name = file.originalFilename ?: "(no-name)"
            if (file.isEmpty) {
                logger.warn("Skipped file: $name (empty)")
                return@forEach
            }
            val lang = detectLang(name, format)
            try {
                val t0 = System.currentTimeMillis()
                file.inputStream.use { input ->
                    // OntModel.read(...) 사용으로 OntModel 미사용 경고 제거
                    ontology.writeTx { txOnt -> txOnt.read(input, null, lang) }
                }
                totalParsingTime += (System.currentTimeMillis() - t0)
                successCount++
            } catch (e: org.apache.jena.riot.RiotException) {
                logger.error("RiotException in file: $name", e)
                failureCount++
            } catch (e: Exception) {
                logger.error("Exception in file: $name, ${e.message}", e)
                failureCount++
            }
        }

        // 업로드 직후 owl:imports 체인을 따라가며 보강 로딩
        val importsSummary = try {
            ontology.loadOntologiesFrom(sources = emptyList(), followImports = true, followNamespaces = true)
        } catch (e: Exception) {
            logger.warn("followImports after upload failed: ${e.message}", e)
            // 실패 시 빈 요약으로 대체
            OntologyLoadSummary(
                totalTried = 0,
                readAuto   = 0,
                failed     = 0,
                loadedIRIs = emptyList(),
                failedIRIs = emptyList(),
                elapsedMs  = 0
            )
        }

        // 총 소요 시간에 imports 보강 시간도 포함하고 싶다면 다음 줄 사용:
        val totalTime = totalParsingTime + importsSummary.elapsedMs

        return ResponseEntity.ok(
            ApiResponse(
                status = "ok",
                timeMs = totalTime,
                data = mapOf(
                    "format" to format,
                    "successCount" to successCount,
                    "failureCount" to failureCount,
                    "totalFiles" to files.size,
                    // imports 보강 결과 요약
                    "imports" to mapOf(
                        "tried" to importsSummary.totalTried,
                        "loaded" to importsSummary.readAuto,
                        "failed" to importsSummary.failed,
                        "elapsedMs" to importsSummary.elapsedMs
                    )
                )
            )
        )
    }

}




// ./gradlew bootRun