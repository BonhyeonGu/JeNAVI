package jenavi

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.jena.ontology.OntModel
import org.apache.jena.ontology.OntModelSpec
import org.apache.jena.ontology.OntDocumentManager
import org.apache.jena.query.ReadWrite
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.RiotException
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.vocabulary.RDF
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.riot.RDFDataMgr
import java.io.File

class Ontology(private val model: OntModel) {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(Ontology::class.java)
        const val PATH_DIR_RDF = "./_RDF"
        const val PATH_DIR_OWL = "./_OWL"
        val PATH_DIR_OWLS: Array<String> = arrayOf(
            //"http://paper.9bon.org/ontologies/sensorthings/1.1.3",
            //"http://paper.9bon.org/ontologies/smartcity/0.2",
            //"http://paper.9bon.org/ontologies/dtom/1.0"
        )

        // 온톨로지 생성
        fun createOntology(useTDB: Boolean): Ontology {
            // 1) DocumentManager: 임포트 추적 + 실패 시 "최종 한 줄"만 찍기
            val ontDocMgr = OntDocumentManager().apply {
                processImports = true
                setReadFailureHandler { uri, _, e ->
                    val msg = e?.message ?: ""
                    // 404 같은 "구체적인 원인"만 통과, 그 외는 조용히 무시
                    if (msg.contains("404") || msg.contains("Not Found", ignoreCase = true)) {
                        logger.warn("Import 404 => $uri")
                    }
                    // 그 외는 로그 출력 안 함
                }
            }
            val ontModelSpec = OntModelSpec(OntModelSpec.OWL_MEM_TRANS_INF).apply {
                documentManager = ontDocMgr
            }

            if (useTDB) {
                val tdbDir = File("./_TDB")
                if (tdbDir.exists()) {
                    tdbDir.deleteRecursively()
                    logger.warn("TDB directory './_TDB' deleted for reinitialization.")
                }

                val dataset = TDB2Factory.connectDataset("./_TDB")
                var ontology: Ontology

                dataset.begin(ReadWrite.WRITE)
                try {
                    val tdbModel = ModelFactory.createOntologyModel(ontModelSpec, dataset.defaultModel)
                    ontology = Ontology(tdbModel)
                    ontology.loadOntologies()     // 최종 요약만 찍도록 정리됨
                    dataset.commit()
                } finally { dataset.end() }

                dataset.begin(ReadWrite.READ)
                try {
                    val tdbModel = ModelFactory.createOntologyModel(ontModelSpec, dataset.defaultModel)
                    ontology = Ontology(tdbModel)
                } finally { dataset.end() }

                return ontology
            } else {
                val memModel = ModelFactory.createOntologyModel(ontModelSpec)
                val ontology = Ontology(memModel)
                ontology.loadOntologies()         // 최종 요약만
                return ontology
            }
        }

    }

    private val readStatusMap: MutableMap<String, Boolean> = mutableMapOf()

    val ontologyModel: OntModel
        get() = model


    fun loadOntologies() {
        // ── 0) Jena RIOT 로그 임시 음소거 ────────────────────────────────────────────
        val loggerCtx = (org.slf4j.LoggerFactory.getILoggerFactory()
                as ch.qos.logback.classic.LoggerContext)
        val targets = listOf(
            "org.apache.jena",
            "org.apache.jena.riot",
            "org.apache.jena.riot.system",
            "org.apache.jena.riot.stream"
        )
        val prevLevels: Map<String, ch.qos.logback.classic.Level?> =
            targets.associateWith { loggerCtx.getLogger(it).level }

        // OFF로 내리면 RIOT의 “[line: 1, col: 1] …” 에러가 콘솔에 안 찍힙니다.
        loggerCtx.getLogger("org.apache.jena").level = ch.qos.logback.classic.Level.WARN
        loggerCtx.getLogger("org.apache.jena.riot").level = ch.qos.logback.classic.Level.OFF
        loggerCtx.getLogger("org.apache.jena.riot.system").level = ch.qos.logback.classic.Level.OFF
        loggerCtx.getLogger("org.apache.jena.riot.stream").level = ch.qos.logback.classic.Level.OFF

        try {
            // ── 1) 집계 카운터 ────────────────────────────────────────────────────
            var totalTried = 0
            var readAsRdfXml = 0
            var readAsTurtle = 0
            var failed = 0

            // ── 2) 기본 URL들: RDF/XML → TURTLE 순으로 한 번씩만 시도(중간 로그 X) ──
            PATH_DIR_OWLS.forEach { url ->
                var ok = false
                totalTried++

                try {
                    model.read(url, "RDF/XML")
                    readAsRdfXml++
                    ok = true
                } catch (_: Exception) {
                    try {
                        model.read(url, "TURTLE")
                        readAsTurtle++
                        ok = true
                    } catch (_: Exception) {
                        // 실패
                    }
                }
                if (!ok) failed++
            }

            // ── 3) 로컬 디렉터리 로딩(기존 유지) ───────────────────────────────────
            //    * 원하시면 readRDF 내부의 파일별 로그도 INFO→DEBUG로 낮추세요.
            readRDF(PATH_DIR_OWL)
            readRDF(PATH_DIR_RDF)

            // ── 4) owl:imports 재귀 로딩도 동일 정책 + 집계 ────────────────────────
            val toVisit: ArrayDeque<String> = ArrayDeque()
            val seen = mutableSetOf<String>()

            run {
                val it = model.listStatements(
                    null as org.apache.jena.rdf.model.Resource?,
                    org.apache.jena.vocabulary.OWL.imports,
                    null as org.apache.jena.rdf.model.RDFNode?
                )
                while (it.hasNext()) {
                    val st = it.nextStatement()
                    val obj = st.`object`
                    if (obj.isResource) {
                        val u = obj.asResource().uri
                        if (u != null && seen.add(u)) toVisit.add(u)
                    }
                }
            }

            while (toVisit.isNotEmpty()) {
                val iri = toVisit.removeFirst()
                var ok = false
                totalTried++

                try {
                    model.read(iri, "RDF/XML")
                    readAsRdfXml++
                    ok = true
                } catch (_: Exception) {
                    try {
                        model.read(iri, "TURTLE")
                        readAsTurtle++
                        ok = true
                    } catch (_: Exception) {
                        // 실패
                    }
                }
                if (!ok) {
                    failed++
                    continue
                }

                // 성공 시, 새로 추가된 모델에서 다음 imports 수집
                val it2 = model.listStatements(
                    null as org.apache.jena.rdf.model.Resource?,
                    org.apache.jena.vocabulary.OWL.imports,
                    null as org.apache.jena.rdf.model.RDFNode?
                )
                while (it2.hasNext()) {
                    val st2 = it2.nextStatement()
                    val obj2 = st2.`object`
                    if (obj2.isResource) {
                        val u2 = obj2.asResource().uri
                        if (u2 != null && seen.add(u2)) toVisit.add(u2)
                    }
                }
            }

            // ── 5) 최종 요약만 출력 ────────────────────────────────────────────────
            logger.info("================================")
            logger.info("================================")
            logger.info("================================")
            logger.info("===== Ontology load summary =====")
            logger.info("Total IRIs tried : $totalTried")
            logger.info("Read as RDF/XML  : $readAsRdfXml")
            logger.info("Read as TURTLE   : $readAsTurtle")
            logger.info("Failed           : $failed")
            calcStatistics()
            logger.info("================================")
            logger.info("================================")
            logger.info("================================")
        } finally {
            // ── 6) 로그 레벨 원복 ────────────────────────────────────────────────
            prevLevels.forEach { (name, level) ->
                loggerCtx.getLogger(name).level = level
            }
        }
    }


    // RDF 읽기
    private fun readRDF(locale: String) {
        val directory = File(locale)
        if (directory.exists() && directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
                logger.info("Read RDF => ${file.absolutePath}")
                try {
                    // 확장자 기반으로 Lang 자동 판별: .ttl, .rdf, .owl, .nt, .nq, .trig, .jsonld 등
                    RDFDataMgr.read(model, file.toURI().toString())
                } catch (e: RiotException) {
                    logger.error("RiotException => ${file.name} : ${e.message}")
                } catch (e: Exception) {
                    logger.error("Unexpected => ${file.name} : ${e.message}")
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

        logger.info("==== Ontology score summary ====")
        logger.info("전체 클래스 수 (정의된 owl:Class): $totalClassCount")
        logger.info("인스턴스가 존재하는 클래스 수: $classWithInstanceCount")
        logger.info("전체 인스턴스 수 (rdf:type 포함): $totalInstances")
        logger.info("Class Richness (CR): %.3f".format(classRichness))
        logger.info("Average Population (AP): %.3f".format(averagePopulation))
    }

    // 파일 입출력 오류 해결
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
