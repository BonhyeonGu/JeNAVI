package jenavi

import jenavi.config.OntologyProperties


import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.jena.ontology.OntModel
import org.apache.jena.ontology.OntModelSpec
import org.apache.jena.ontology.OntDocumentManager
import org.apache.jena.query.Dataset
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.RiotException
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.system.Txn
import org.apache.jena.rdf.model.Resource
import org.apache.jena.riot.RDFFormat
import org.apache.jena.vocabulary.OWL

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.Path

data class OntologyLoadSummary(
    val totalTried: Int,
    val readAuto: Int,
    val failed: Int,
    val loadedIRIs: List<String>,
    val failedIRIs: List<String>,
    val elapsedMs: Long
)

private data class LoadCounters(
    var totalTried: Int = 0,
    var readAuto: Int = 0,
    var failed: Int = 0,
    val loadedIRIs: MutableList<String> = mutableListOf(),
    val failedIRIs: MutableList<String> = mutableListOf()
)

class Ontology(private var model: OntModel) {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(Ontology::class.java)
        const val PATH_DIR_RDF = "./_RDF"
        const val PATH_DIR_OWL = "./_OWL"
        const val PATH_DIR_TDB = "./_TDB"
        val PATH_DIR_OWLS: Array<String> = arrayOf(
            //"http://paper.9bon.org/ontologies/sensorthings/1.1.3",
            //"http://paper.9bon.org/ontologies/smartcity/0.2",
            //"http://paper.9bon.org/ontologies/dtom/1.0"
        )

        // 온톨로지 생성
        fun createForStartup(useTDB: Boolean): Ontology {
            // 래핑용: 생성자에서 imports 자동 로딩 금지(닫힌 그래프 접근 방지)
            val wrapDocMgr = OntDocumentManager().apply { processImports = false }
            val wrapSpec   = OntModelSpec(OntModelSpec.OWL_MEM_TRANS_INF).apply { documentManager = wrapDocMgr }

            return if (!useTDB) {
                // 인메모리: 곧바로 로딩
                val loadDocMgr = OntDocumentManager().apply { processImports = true }
                val loadSpec   = OntModelSpec(OntModelSpec.OWL_MEM_TRANS_INF).apply { documentManager = loadDocMgr }
                val mem        = ModelFactory.createOntologyModel(loadSpec)
                Ontology(mem).also { it.loadDefaultOntologies(followImports = true) }
            } else {
                // TDB: 기존 내용을 보존한 채 래핑만
                val ds = TDB2Factory.connectDataset(PATH_DIR_TDB)
                val tdbModel = Txn.calculateRead(ds) {
                    ModelFactory.createOntologyModel(wrapSpec, ds.defaultModel)
                }
                Ontology(tdbModel).also { it.attachDataset(ds) }
            }
        }
    }

    // ================================================================================================

    val ontologyModel: OntModel
        get() = model

    private var tdbDataset: Dataset? = null
    fun attachDataset(ds: Dataset?) { this.tdbDataset = ds }


    // --- 외부 공개 API (모델 중심/데이터셋 중심) ---
    // Ontology.kt 상단 어딘가에 캐시할 스펙을 준비
    private val wrapDocMgr = OntDocumentManager().apply { processImports = false }
    private val wrapSpec   = OntModelSpec(OntModelSpec.OWL_MEM_TRANS_INF).apply {
        documentManager = wrapDocMgr
    }

    // 기존: tdbDataset 있으면 Txn 안에서 this.model을 넘겼음 (문제 원인)
    // 변경: TDB면 Txn 안에서 매번 새 OntModel로 ds.defaultModel을 감싸서 넘김
    fun <T> readTx(block: (OntModel) -> T): T {
        val ds = tdbDataset
        return if (ds != null) {
            Txn.calculateRead(ds) {
                val txModel = ModelFactory.createOntologyModel(wrapSpec, ds.defaultModel)
                block(txModel)
            }
        } else {
            block(this.model)
        }
    }


    fun <T> writeTx(block: (OntModel) -> T): T {
        val ds = tdbDataset
        return if (ds != null) {
            Txn.calculateWrite(ds) {
                // 1) TDB와 분리된 임시 OntModel (추론 켬, imports off)
                val tempOnt = ModelFactory.createOntologyModel(wrapSpec)
                // 2) 호출자가 tempOnt 에 자유롭게 write(read/ add/ etc.)
                val res: T = block(tempOnt)
                // 3) 트랜잭션 끝에 한 번에 TDB default 그래프에 병합 (추가만)
                ds.defaultModel.add(tempOnt.baseModel)
                res
            }
        } else {
            // 인메모리는 기존 모델에 직접
            block(this.model)
        }
    }


    // ================================================================================================


    // 1) readRDF: tx 모델을 받아서 그걸로만 읽기
    fun readRDF(pathOrDir: String, recursive: Boolean = true): Int =
        writeTx { txModel ->
            val f = File(pathOrDir)
            var loaded = 0
            if (f.isDirectory) {
                val files = if (recursive) f.walkTopDown() else f.walk()
                files.filter { it.isFile }.forEach { file ->
                    loaded += readOneRdfFile(txModel, file)   // ⬅ txModel 전달
                }
            } else if (f.isFile) {
                loaded += readOneRdfFile(txModel, f)          // ⬅ txModel 전달
            } else {
                logger.warn("readRDF: not found: $pathOrDir")
            }
            loaded
        }


    // 2) readOneRdfFile: 필드 model 대신 txModel 사용
    private fun readOneRdfFile(txModel: OntModel, file: File): Int = try {
        logger.info("Read RDF => ${file.absolutePath}")
        RDFDataMgr.read(txModel, file.toURI().toString()) // ⬅ txModel 사용
        1
    } catch (e: RiotException) {
        logger.error("RiotException => ${file.name} : ${e.message}"); 0
    } catch (e: Exception) {
        logger.error("Unexpected => ${file.name} : ${e.message}"); 0
    }


    // ================================================================================================


    // 온톨로지 덤프
    fun saveOntology(path: String, includeNamedGraphs: Boolean = false): Long {
        val p = Paths.get(path)
        p.parent?.let { Files.createDirectories(it) }

        val lower = path.lowercase()
        val ds = tdbDataset

        return if (includeNamedGraphs && ds != null) {
            // 데이터셋 전체 저장 (TRiG/N-Quads/…)
            val fmt = when {
                lower.endsWith(".trig") -> RDFFormat.TRIG_PRETTY
                lower.endsWith(".nq")   -> RDFFormat.NQUADS_UTF8
                lower.endsWith(".trix") -> RDFFormat.TRIX
                else                    -> RDFFormat.TRIG_PRETTY
            }
            Txn.calculateRead(ds) {
                Files.newOutputStream(p).use { out ->
                    RDFDataMgr.write(out, ds.asDatasetGraph(), fmt)
                }
                Files.size(p)
            }
        } else {
            // 현재 모델(디폴트 그래프)만 저장 (TTL/RDF/XML/JSON-LD/N-Triples)
            val fmt = when {
                lower.endsWith(".ttl")     -> RDFFormat.TURTLE_PRETTY
                lower.endsWith(".rdf") ||
                        lower.endsWith(".owl") ||
                        lower.endsWith(".xml")     -> RDFFormat.RDFXML_PLAIN
                lower.endsWith(".jsonld")  -> RDFFormat.JSONLD_PRETTY
                lower.endsWith(".nt")      -> RDFFormat.NTRIPLES_UTF8
                else                       -> RDFFormat.TURTLE_PRETTY
            }
            readTx { m ->
                Files.newOutputStream(p).use { out ->
                    RDFDataMgr.write(out, m, fmt)
                }
                Files.size(p)
            }
        }
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


    // ================================================================================================


    // TDB 용량 표시
    fun tdbDiskUsageBytes(): Long {
        val dir = File(PATH_DIR_TDB)
        if (!dir.exists() || !dir.isDirectory) {
            logger.info("TDB path not found or not a directory: $PATH_DIR_TDB (usage = 0 B)")
            return 0L
        }

        var total = 0L
        var files = 0
        var dirs = 0

        Files.walk(dir.toPath()).use { stream ->
            stream.forEach { p ->
                try {
                    if (Files.isDirectory(p)) {
                        dirs++
                    } else if (Files.isRegularFile(p)) {
                        total += kotlin.runCatching { Files.size(p) }.getOrDefault(0L)
                        files++
                    }
                } catch (_: Exception) {
                    // 권한/락 문제는 무시
                }
            }
        }

//        logger.info("TDB disk usage => ${humanReadable(total)} ($total bytes) | files=$files, dirs=$dirs, path=$PATH_DIR_TDB")
        return total
    }


    // 용량 보기 편하게
    private fun humanReadable(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
        var v = bytes.toDouble()
        var i = -1
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return String.format(java.util.Locale.US, "%.2f %s", v, units[i])
    }


    // ================================================================================================

    // 온톨로지 초기화
    fun clearJenaIoCachesCompat() {
        // 문서 캐시
        runCatching { OntDocumentManager.getInstance().clearCache() }

        // 1차: StreamManager.makeDefault() + setGlobal(...) 시도
        val ok1 = runCatching {
            val cls = Class.forName("org.apache.jena.riot.system.stream.StreamManager")
            val makeDefault = cls.getMethod("makeDefault")
            val sm = makeDefault.invoke(null)
            val setGlobal = cls.getMethod("setGlobal", cls)
            setGlobal.invoke(null, sm)
        }.isSuccess

        if (!ok1) {
            // 2차: 기본 생성자로 StreamManager 만들고 setGlobal(...) 시도
            val ok2 = runCatching {
                val cls = Class.forName("org.apache.jena.riot.system.stream.StreamManager")
                val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
                val sm = ctor.newInstance()

                // (가능하면 LocationMapper도 초기화)
                runCatching {
                    val lmCls = Class.forName("org.apache.jena.riot.system.stream.LocationMapper")
                    val lmCtor = lmCls.getDeclaredConstructor().apply { isAccessible = true }
                    val lm = lmCtor.newInstance()
                    val setLM = cls.getMethod("setLocationMapper", lmCls)
                    setLM.invoke(sm, lm)
                }

                val setGlobal = cls.getMethod("setGlobal", cls)
                setGlobal.invoke(null, sm)
            }.isSuccess

            if (!ok2) {
                // 3차: 최후수단 — 전역 LocationMapper만 리셋
                runCatching {
                    val lmCls = Class.forName("org.apache.jena.riot.system.stream.LocationMapper")
                    val lmCtor = lmCls.getDeclaredConstructor().apply { isAccessible = true }
                    val lm = lmCtor.newInstance()
                    val setGlobalLM = lmCls.getMethod("setGlobalLocationMapper", lmCls)
                    setGlobalLM.invoke(null, lm)
                }
            }
        }
    }


    fun truncateTDB(): Long {
        val t0 = System.currentTimeMillis()
        if (!OntologyProperties.useTDB) return 0L
        val ds = tdbDataset ?: TDB2Factory.connectDataset(PATH_DIR_TDB)
        try {
            Txn.executeWrite(ds) { ds.asDatasetGraph().clear() }
        } finally {
            if (ds !== tdbDataset) runCatching { ds.close() }
        }
        return System.currentTimeMillis() - t0
    }


    fun fullResetAndReload(): Long {
        val t0 = System.currentTimeMillis()

        truncateTDB()
        clearJenaIoCachesCompat()   // ← 여기!

        loadDefaultOntologies()            // txModel만 사용하도록 유지

        return System.currentTimeMillis() - t0
    }




    // =================================================================================================

    private fun readIntoOnt(ont: OntModel, iri: String, c: LoadCounters) {
        c.totalTried++
        runCatching {
            // OntModel 은 Model 을 구현하므로 그대로 전달해도 됩니다.
            RDFDataMgr.read(ont, iri)
            c.readAuto++
        }.onFailure {
            c.failed++
            logger.warn("Read failed: $iri (${it.message})")
        }
    }


    // 경로(디렉터리/파일)를 받아 txModel(OntModel)로 일괄 로드
    private fun readPathIntoOnt(ont: OntModel, pathStr: String, c: LoadCounters) {
        val p = Paths.get(pathStr)
        if (!Files.exists(p)) {
            val lower = pathStr.lowercase()
            // 경로가 없더라도 URL처럼 보이면 URL 로더로 재시도
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:")) {
                readIntoOnt(ont, pathStr, c)
            } else {
                logger.warn("readPathIntoOnt: path not found => $pathStr")
            }
            return
        }

        if (Files.isDirectory(p)) {
            Files.walk(p).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { f ->
                    readIntoOnt(ont, f.toUri().toString(), c)
                }
            }
        } else {
            // 단일 파일
            readIntoOnt(ont, p.toUri().toString(), c)
        }
    }

    fun loadOntologiesFrom(
        sources: List<String> = emptyList(),   // 경로(디렉터리/파일) 또는 URL
        followImports: Boolean = true,
        followNamespaces: Boolean = false
    ): OntologyLoadSummary {
        val t0 = System.currentTimeMillis()
        return writeTx { txModel: OntModel ->
            val c = LoadCounters()

            // 중복 방지용 (imports와 namespace 모두 공용)
            val seen = mutableSetOf<String>()

            // ---- 1) 입력 소스 로드 (URL vs 경로 구분) ----
            sources.forEach { src ->
                val lower = src.lowercase()
                if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:")) {
                    if (seen.add(src)) readIntoOnt(txModel, src, c)
                } else {
                    // 파일/디렉터리 자동 처리
                    if (seen.add(java.io.File(src).canonicalPath)) {
                        readPathIntoOnt(txModel, src, c)
                    }
                }
            }

            // ---- 2) 네임스페이스 URI 따라가며 보강 (prefix map) ----
            if (followNamespaces) {
                // 현재 모델의 prefix -> namespace URI 매핑에서 http(s)만 추출
                val nsUris = txModel.nsPrefixMap.values
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    // namespace URI는 보통 끝이 '/' 또는 '#' 이지만, 그대로 dereference 시도
                    .distinct()

                nsUris.forEach { ns ->
                    if (seen.add(ns)) {
                        readIntoOnt(txModel, ns, c)
                    }
                }
            }

            // ---- 3) owl:imports 따라가며 보강 (sources/namespace가 비어 있어도 수행 가능) ----
            if (followImports) {
                val toVisit: ArrayDeque<String> = ArrayDeque()

                // 초기 import 수집(현재까지 읽힌 모델 기준)
                run {
                    val it = txModel.listStatements(null as Resource?, OWL.imports, null as RDFNode?)
                    try {
                        while (it.hasNext()) {
                            val u = it.nextStatement().`object`.asResource().uri
                            if (u != null && seen.add(u)) toVisit.add(u)
                        }
                    } finally {
                        runCatching { it.close() }
                    }
                }

                // 성공적으로 읽힌 경우에만 확장
                while (toVisit.isNotEmpty()) {
                    val iri = toVisit.removeFirst()
                    val before = c.readAuto
                    readIntoOnt(txModel, iri, c)
                    if (c.readAuto > before) {
                        // 새로 읽은 문서에서 추가 imports가 생겼을 수 있으므로 다시 수집
                        val it2 = txModel.listStatements(null as Resource?, OWL.imports, null as RDFNode?)
                        try {
                            while (it2.hasNext()) {
                                val u2 = it2.nextStatement().`object`.asResource().uri
                                if (u2 != null && seen.add(u2)) toVisit.add(u2)
                            }
                        } finally {
                            runCatching { it2.close() }
                        }
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - t0
            logger.info("===== Ontology load summary =====")
            logger.info("Total IRIs tried : ${c.totalTried}")
            logger.info("Read (auto)      : ${c.readAuto}")
            logger.info("Failed           : ${c.failed}")

            OntologyLoadSummary(
                totalTried = c.totalTried,
                readAuto   = c.readAuto,
                failed     = c.failed,
                loadedIRIs = c.loadedIRIs.toList(),
                failedIRIs = c.failedIRIs.toList(),
                elapsedMs  = elapsed
            )
        }
    }


    // 기본(고정 경로) 버전은 이걸로 래핑해서 재사용
    fun loadDefaultOntologies(followImports: Boolean = true): OntologyLoadSummary =
        loadOntologiesFrom(
            sources = PATH_DIR_OWLS.toList() + listOf(PATH_DIR_OWL, PATH_DIR_RDF),
            followImports = followImports
        )
}
