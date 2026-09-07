package jenavi

import jenavi.config.OntologyProperties

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.apache.jena.ontology.OntModel
import org.apache.jena.ontology.OntModelSpec
import org.apache.jena.ontology.OntDocumentManager
import org.apache.jena.query.Dataset
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.riot.RiotException
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.system.Txn
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.vocabulary.OWL

import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import org.apache.jena.reasoner.rulesys.GenericRuleReasoner
import org.apache.jena.reasoner.rulesys.Rule

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
            val docMgr = OntDocumentManager().apply { processImports = false } // ★ 통일
            val spec   = OntModelSpec(OntModelSpec.OWL_MEM_TRANS_INF).apply { documentManager = docMgr }

            return if (!useTDB) {
                Ontology(ModelFactory.createOntologyModel(spec))
            } else {
                val ds = TDB2Factory.connectDataset(PATH_DIR_TDB)
                Ontology(ModelFactory.createOntologyModel(spec, ds.defaultModel)).also { it.attachDataset(ds) }
            }
        }
    }

    // ================================================================================================

    val ontologyModel: OntModel
        get() = model

    private var tdbDataset: Dataset? = null
    fun attachDataset(ds: Dataset?) { this.tdbDataset = ds }

    private val wrapDocMgr = OntDocumentManager().apply { processImports = false }
    private val wrapSpec   = OntModelSpec(OntModelSpec.OWL_MEM_TRANS_INF).apply {
        documentManager = wrapDocMgr
    }

    private val rwLock = ReentrantReadWriteLock()

    fun <T> readTx(block: (OntModel) -> T): T {
        val ds = tdbDataset
        return if (ds != null) {
            Txn.calculateRead(ds) {
                val txModel = ModelFactory.createOntologyModel(wrapSpec, ds.defaultModel)
                block(txModel)
            }
        } else {
            rwLock.read { block(this.model) }
        }
    }

    fun <T> writeTx(block: (OntModel) -> T): T {
        val ds = tdbDataset
        return if (ds != null) {
            Txn.calculateWrite(ds) {
                val txModel = ModelFactory.createOntologyModel(wrapSpec, ds.defaultModel)
                block(txModel)
            }
        } else {
            rwLock.write { block(this.model) }
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
                    loaded += readOneRdfFile(txModel, file)
                }
            } else if (f.isFile) {
                loaded += readOneRdfFile(txModel, f)
            } else {
                logger.warn("readRDF: not found: $pathOrDir")
            }
            loaded
        }

    private fun readOneRdfFile(txModel: OntModel, file: File): Int = try {
        logger.info("Read RDF => ${file.absolutePath}")
        RDFDataMgr.read(txModel, file.toURI().toString())
        1
    } catch (e: RiotException) {
        logger.error("RiotException => ${file.name} : ${e.message}")
        0
    } catch (e: Exception) {
        logger.error("Unexpected => ${file.name} : ${e.message}")
        0
    }

    // ================================================================================================

    fun saveOntology(path: String, includeNamedGraphs: Boolean = false): Long {
        val p = Paths.get(path)
        p.parent?.let { Files.createDirectories(it) }

        val lower = path.lowercase()
        val ds = tdbDataset

        return if (includeNamedGraphs && ds != null) {
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

    // ================================================================================================

    fun tdbDiskUsageBytes(): Long {
        val dir = File(PATH_DIR_TDB)
        if (!dir.exists() || !dir.isDirectory) {
            logger.info("TDB path not found or not a directory: $PATH_DIR_TDB (usage = 0 B)")
            return 0L
        }

        var total = 0L
        Files.walk(dir.toPath()).use { stream ->
            stream.forEach { p ->
                try {
                    if (Files.isRegularFile(p)) {
                        total += runCatching { Files.size(p) }.getOrDefault(0L)
                    }
                } catch (_: Exception) {
                }
            }
        }
        return total
    }

    // ================================================================================================

    fun clearJenaIoCachesCompat() {
        runCatching { OntDocumentManager.getInstance().clearCache() }

        val ok1 = runCatching {
            val cls = Class.forName("org.apache.jena.riot.system.stream.StreamManager")
            val makeDefault = cls.getMethod("makeDefault")
            val sm = makeDefault.invoke(null)
            val setGlobal = cls.getMethod("setGlobal", cls)
            setGlobal.invoke(null, sm)
        }.isSuccess

        if (!ok1) {
            val ok2 = runCatching {
                val cls = Class.forName("org.apache.jena.riot.system.stream.StreamManager")
                val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
                val sm = ctor.newInstance()

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

    private fun resetInMemoryModel() {
        runCatching { this.model.close() }
        this.model = ModelFactory.createOntologyModel(wrapSpec)
    }

    fun fullResetAndReload(): Long {
        val t0 = System.currentTimeMillis()

        if (OntologyProperties.useTDB) {
            truncateTDB()
        } else {
            resetInMemoryModel()
        }

        clearJenaIoCachesCompat()
        loadDefaultOntologies(followImports = true)

        return System.currentTimeMillis() - t0
    }

    // =================================================================================================
    // URL 로더 (fallback + HTTP bytes 로딩 + 파싱 fallback)
    // =================================================================================================

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS) // 303 등 자동 추적
        .build()

    private val ACCEPT_RDF =
        "text/turtle, application/rdf+xml;q=0.95, application/ld+json;q=0.9, application/n-triples;q=0.8, */*;q=0.1"

    // RDF로 읽을 의미가 거의 없는 것(로그/오류 소음 방지)
    private fun shouldSkipIri(url: String): Boolean {
        val base = url.substringBefore('#')
        return base == "http://www.w3.org/2001/XMLSchema" ||
                base == "http://www.w3.org/XML/1998/namespace"
    }

    private fun guessLangByExt(url: String): Lang? {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".ttl")    -> Lang.TURTLE
            lower.endsWith(".rdf")    -> Lang.RDFXML
            lower.endsWith(".xml")    -> Lang.RDFXML
            lower.endsWith(".owl")    -> Lang.RDFXML
            lower.endsWith(".nt")     -> Lang.NTRIPLES
            lower.endsWith(".trig")   -> Lang.TRIG
            lower.endsWith(".nq")     -> Lang.NQUADS
            lower.endsWith(".jsonld") -> Lang.JSONLD
            else -> null
        }
    }

    // 요구사항: 실패하면 .ttl .rdf .xml 을 시도 (순서 고정)
    private fun buildFallbackUrls(iri: String): List<String> {
        val raw = iri.trim()
        val base = raw.substringBefore("#").trim()

        val set = LinkedHashSet<String>()

        fun add(u: String) {
            if (u.isNotBlank()) set.add(u)
        }

        add(raw)
        add(base)

        // base가 "/"로 끝나면 base.ttl 같은건 의미 없으므로 제외
        if (!base.endsWith("/")) {
            val lower = base.lowercase()
            val hasKnown =
                lower.endsWith(".ttl") || lower.endsWith(".rdf") || lower.endsWith(".xml") || lower.endsWith(".owl")

            val baseNoExt = if (hasKnown && base.contains(".")) base.substringBeforeLast(".") else base

            // base 자체가 확장자 없으면 바로 base.ttl/rdf/xml
            if (!hasKnown) {
                add("$base.ttl")
                add("$base.rdf")
                add("$base.xml")
            } else {
                // base가 .owl 등일 때도, "확장자 제거 + .ttl/.rdf/.xml" 재시도 가능하게
                add("$baseNoExt.ttl")
                add("$baseNoExt.rdf")
                add("$baseNoExt.xml")
            }
        }

        return set.toList()
    }

    private data class HttpFetch(val ok: Boolean, val statusOrErr: String, val contentType: String?, val bytes: ByteArray)

    private fun httpGetBytes(url: String): HttpFetch {
        return try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", ACCEPT_RDF)
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build()

            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
            val code = res.statusCode()
            val ct = res.headers().firstValue("Content-Type").orElse(null)
            val bytes = res.body() ?: ByteArray(0)

            if (code in 200..299) HttpFetch(true, "$code", ct, bytes)
            else HttpFetch(false, "$code", ct, bytes)
        } catch (e: Exception) {
            HttpFetch(false, e.message ?: "http error", null, ByteArray(0))
        }
    }

    private fun looksLikeHtml(contentType: String?, bytes: ByteArray): Boolean {
        if (contentType?.contains("text/html", ignoreCase = true) == true) return true
        val head = runCatching { bytes.take(200).toByteArray().toString(Charsets.UTF_8) }.getOrDefault("")
        val t = head.trimStart()
        return t.startsWith("<!doctype", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)
    }

    private fun guessLangByContentType(contentType: String?): Lang? {
        val ct = contentType?.lowercase() ?: return null
        return when {
            ct.contains("text/turtle") -> Lang.TURTLE
            ct.contains("application/rdf+xml") -> Lang.RDFXML
            ct.contains("application/ld+json") -> Lang.JSONLD
            ct.contains("application/n-triples") -> Lang.NTRIPLES
            else -> null
        }
    }

    private fun parseWithFallback(ont: OntModel, baseUrl: String, contentType: String?, bytes: ByteArray): Pair<Boolean, String> {
        if (bytes.isEmpty()) return false to "empty body"
        if (looksLikeHtml(contentType, bytes)) return false to "html response"

        val first = guessLangByExt(baseUrl) ?: guessLangByContentType(contentType)

        val tries = buildList {
            if (first != null) add(first)
            addAll(listOf(Lang.TURTLE, Lang.RDFXML, Lang.JSONLD, Lang.NTRIPLES).filter { it != first })
        }

        var lastErr = "parse failed"
        for (lang in tries) {
            val ok = runCatching {
                ByteArrayInputStream(bytes).use { ins ->
                    RDFDataMgr.read(ont, ins, baseUrl, lang)
                }
            }.onFailure { e ->
                lastErr = e.message ?: "parse error"
            }.isSuccess

            if (ok) return true to ""
        }
        return false to lastErr
    }

    private fun tryReadOneCandidate(ont: OntModel, url: String): Pair<Boolean, String> {
        val u = url.trim()
        if (u.isBlank()) return false to "blank url"
        if (shouldSkipIri(u)) return true to "skipped"

        // file: 은 Jena가 잘 처리함
        if (u.startsWith("file:", ignoreCase = true)) {
            return runCatching { RDFDataMgr.read(ont, u) }.fold(
                onSuccess = { true to "" },
                onFailure = { false to (it.message ?: "file read error") }
            )
        }

        // 1) 1차: 기존 방식(Jena가 직접 URL을 열어서 읽음)
        val lang = guessLangByExt(u)
        val ok1 = runCatching {
            if (lang != null) RDFDataMgr.read(ont, u, lang) else RDFDataMgr.read(ont, u)
        }.isSuccess
        if (ok1) return true to ""

        // 2) 2차: 브라우저에선 열리는데 Jena가 Content-Type/redirect 때문에 실패하는 케이스 대비
        if (u.startsWith("http://", true) || u.startsWith("https://", true)) {
            val f = httpGetBytes(u)
            if (!f.ok) return false to f.statusOrErr

            val (ok2, err2) = parseWithFallback(ont, u, f.contentType, f.bytes)
            if (ok2) return true to ""
            return false to err2
        }

        return false to "read failed"
    }

    private fun readIntoOnt(ont: OntModel, iri: String, c: LoadCounters) {
        val candidates = buildFallbackUrls(iri)

        var lastErr = "unknown"
        for (u in candidates) {
            c.totalTried++
            val (ok, err) = tryReadOneCandidate(ont, u)
            if (ok) {
                // skipped 는 성공 취급(카운트/리스트는 원본 IRI 기준으로만)
                if (err != "skipped") c.readAuto++
                c.loadedIRIs.add(iri)
                return
            } else {
                lastErr = err
            }
        }

        c.failed++
        c.failedIRIs.add(iri)
        logger.warn("Read failed: $iri (tried=${candidates.joinToString()} | last=$lastErr)")
    }

    // =================================================================================================

    private fun readPathIntoOnt(ont: OntModel, pathStr: String, c: LoadCounters) {
        val p = Paths.get(pathStr)
        if (!Files.exists(p)) {
            val lower = pathStr.lowercase()
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
            readIntoOnt(ont, p.toUri().toString(), c)
        }
    }

    fun loadOntologiesFrom(
        sources: List<String> = emptyList(),
        followImports: Boolean = true,
        followNamespaces: Boolean = false
    ): OntologyLoadSummary {
        val t0 = System.currentTimeMillis()
        return writeTx { txModel: OntModel ->
            val c = LoadCounters()
            val seen = mutableSetOf<String>()

            // 1) 입력 소스 로드
            sources.forEach { src ->
                val lower = src.lowercase()
                if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:")) {
                    if (seen.add(src)) readIntoOnt(txModel, src, c)
                } else {
                    if (seen.add(File(src).canonicalPath)) {
                        readPathIntoOnt(txModel, src, c)
                    }
                }
            }

            // 2) 네임스페이스 따라가며 보강
            if (followNamespaces) {
                val nsUris = txModel.nsPrefixMap.values
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .distinct()

                nsUris.forEach { ns ->
                    if (seen.add(ns)) readIntoOnt(txModel, ns, c)
                }
            }

            // 3) owl:imports 따라가며 보강
            if (followImports) {
                val toVisit: ArrayDeque<String> = ArrayDeque()

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

                while (toVisit.isNotEmpty()) {
                    val iri = toVisit.removeFirst()
                    val before = c.readAuto
                    readIntoOnt(txModel, iri, c)
                    if (c.readAuto > before) {
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

    fun loadDefaultOntologies(followImports: Boolean = true): OntologyLoadSummary =
        loadOntologiesFrom(
            sources = PATH_DIR_OWLS.toList() + listOf(PATH_DIR_OWL, PATH_DIR_RDF),
            followImports = followImports
        )

    fun applyRulesAndMaterialize(ruleString: String): Pair<Long, Long> {
        val t0 = System.currentTimeMillis()

        // 1) 룰 파싱
        val rules = Rule.parseRules(ruleString)
        val reasoner = GenericRuleReasoner(rules)

        // 2) 트랜잭션 내에서 추론 및 저장 (writeTx 활용)
        val addedStatementsCount = writeTx { baseModel ->
            // 추론 모델(InfModel) 생성
            val infModel = ModelFactory.createInfModel(reasoner, baseModel)

            // 추론 실행 준비
            infModel.prepare()

            // 베이스 모델에 이미 있는 내용을 제외하고, "새롭게 추론된 내용"만 추출
            val deductions = infModel.deductionsModel
            val count = deductions.size()

            // 베이스 모델에 반영 (Materialize)
            if (count > 0) {
                baseModel.add(deductions)
            }

            count
        }

        val elapsed = System.currentTimeMillis() - t0
        logger.info("Jena Rule applied: inferred $addedStatementsCount triples in $elapsed ms")

        return Pair(addedStatementsCount, elapsed)
    }
}
