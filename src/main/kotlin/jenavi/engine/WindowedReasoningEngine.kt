package jenavi.engine

import jenavi.Ontology
import jenavi.mqtt.ObservationEvent
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.rdf.model.Model
import org.apache.jena.vocabulary.RDF
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ③ 연구 코어. 스트림을 STA 1.3 구조대로 온톨로지에 반영한다.
 *
 * 관측 1건 = Observation 서브그래프. 도착 시 attach(insert), 윈도우 만료 시 detach(delete).
 *   MultiDatastream --hasObservation--> Observation --hasResult--> Result --hasValue--> "v"
 *   Result --hasObservedProperty--> op --hasUnitOfMeasurement--> uom
 *   IndexPoint(MDS,op,uom) --pointToResult--> Result   [IndexPoint는 OWL상 per-MultiDatastream]
 * per-MDS IndexPoint마다 윈도우 distributive 집계(hasWindowedAverage 등)를 유지.
 * 교차센서 융합(도시 온도)은 같은 metadata를 공유하는 IndexPoint들의 2차 집계(snapshot).
 *
 * 비용: 슬라이드당 O(Δ + k). m(누적) 무관, 그래프 거주량은 W에 한정(만료로 detach).
 */
@Component
class WindowedReasoningEngine(
    private val registry: MetadataRegistry,
    private val ontology: Ontology,
) {
    private val logger = LoggerFactory.getLogger(WindowedReasoningEngine::class.java)
    private val S = Metadata.STA

    private val windowMs = (System.getenv("WINDOW_SECONDS")?.toLongOrNull() ?: 30L) * 1000L
    private val slideMs = (System.getenv("SLIDE_SECONDS")?.toLongOrNull() ?: 2L) * 1000L

    private data class Res(val idx: Int, val op: String, val uom: String, val value: Double, val valueStr: String)
    private data class Obs(val mds: String, val tMs: Long, val obsId: String, val phenomenonTime: String, val results: List<Res>)
    private class AggEntry(val mds: String, val op: String, val uom: String) { var sum = 0.0; var count = 0; var last = "" }

    private val pending = ConcurrentLinkedQueue<Obs>()
    private val resident = ArrayDeque<Obs>()
    private val agg = HashMap<String, AggEntry>()  // ipUri -> aggregate
    private val obsSeq = AtomicLong(0)
    private val lock = Any()

    @Volatile private var enabled = false
    @Volatile private var lastAsOf = "-"
    @Volatile private var lastMaterializeMs = 0L
    private var exec: ScheduledExecutorService? = null

    // 라이브 집계 산출 방식: indexed(IndexPoint 유지값 읽기 O(k)) | naive(전 Result 스캔 O(W)).
    // 공정한 벤치마크 비교는 /experiment 라우트에서 격리 실행(캐시/순서/유지비용 오염 배제).
    @Volatile var aggMode = "indexed"
    @Volatile private var computeUs = 0L
    fun setMode(v: String) { aggMode = if (v == "naive") "naive" else "indexed" }

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun enable() {
        if (enabled) return
        registry.seedExperimentDefaultsIfEmpty()
        enabled = true
        val e = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "windowed-engine-slide").apply { isDaemon = true } }
        e.scheduleAtFixedRate({ runCatching { slide() }.onFailure { logger.warn("slide failed: {}", it.message) } },
            slideMs, slideMs, TimeUnit.MILLISECONDS)
        exec = e
        logger.info("WindowedReasoningEngine ENABLED (window={}s, slide={}s, edges={})", windowMs / 1000, slideMs / 1000, registry.size())
    }

    @Synchronized
    fun disable() {
        if (!enabled) return
        enabled = false
        exec?.shutdownNow(); exec = null
        logger.info("WindowedReasoningEngine DISABLED")
    }

    /** 인입 이벤트 → Observation으로 버퍼링(슬라이드에서 일괄 반영). */
    fun onEvent(event: ObservationEvent) {
        if (!enabled) return
        val mds = event.multiDatastreamId ?: return
        val now = System.currentTimeMillis() // event-time은 후속: 지금은 arrival-time
        val pt = event.phenomenonTime ?: Instant.ofEpochMilli(now).toString()
        val results = event.result.mapIndexedNotNull { i, s ->
            val v = s.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val meta = registry.resolve(mds, i) ?: return@mapIndexedNotNull null
            Res(i, meta.op, meta.uom, v, s)
        }
        if (results.isEmpty()) return
        val obsId = event.observationId ?: "s${obsSeq.incrementAndGet()}"
        pending.add(Obs(mds, now, obsId, pt, results))
    }

    private fun drainPending(): List<Obs> {
        val out = ArrayList<Obs>()
        while (true) { out.add(pending.poll() ?: break) }
        return out
    }

    private fun slide() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val toInsert = drainPending()
            val t0 = System.currentTimeMillis()
            ontology.writeTx { m ->
                toInsert.forEach { obs -> insertObs(m, obs); resident.addLast(obs); obs.results.forEach { aggAdd(obs.mds, it) } }
                val cutoff = now - windowMs
                while (resident.isNotEmpty() && resident.first().tMs < cutoff) {
                    val ex = resident.removeFirst(); deleteObs(m, ex); ex.results.forEach { aggSub(ex.mds, it) }
                }
                if (aggMode == "naive") {
                    lastAsOf = Instant.ofEpochMilli(now).toString()
                    computeUs = timeUs { computeNaive(m) }        // O(W) 스캔
                } else {
                    materialize(m, now)
                    computeUs = timeUs { computeIndexed() }        // O(k) 읽기
                }
            }
            lastMaterializeMs = System.currentTimeMillis() - t0
        }
    }

    private fun aggAdd(mds: String, r: Res) {
        val uri = Metadata.indexPointUri(mds, Metadata(r.op, r.uom))
        agg.getOrPut(uri) { AggEntry(mds, r.op, r.uom) }.let { it.sum += r.value; it.count++; it.last = r.valueStr }
    }

    private fun aggSub(mds: String, r: Res) {
        val uri = Metadata.indexPointUri(mds, Metadata(r.op, r.uom))
        agg[uri]?.let { it.sum -= r.value; it.count--; if (it.count <= 0) it.last = "" }
    }

    // ---- RDF (STA 1.3) ----
    private fun mdsUri(mds: String) = Metadata.mdsUri(mds)
    private fun obsUri(o: Obs) = Metadata.obsUri(o.mds, o.obsId)
    private fun resUri(o: Obs, r: Res) = Metadata.resUri(o.mds, o.obsId, r.idx)

    private fun insertObs(m: Model, o: Obs) {
        fun p(local: String) = m.createProperty(S + local)
        fun c(local: String) = m.createResource(S + local)
        val mdsRes = m.createResource(mdsUri(o.mds))
        val obsRes = m.createResource(obsUri(o))
        val ptLit = m.createTypedLiteral(o.phenomenonTime, XSDDatatype.XSDdateTimeStamp)

        obsRes.addProperty(RDF.type, c("Observation"))
        mdsRes.addProperty(p("hasObservation"), obsRes)
        obsRes.addProperty(p("isObservationOfMultiDatastream"), mdsRes)
        obsRes.addProperty(p("hasPhenomenonTime"), ptLit)
        obsRes.addProperty(p("hasResultTime"), ptLit)

        o.results.forEach { r ->
            val meta = Metadata(r.op, r.uom)
            val opRes = m.createResource(meta.opUri()).addProperty(RDF.type, c("ObservedProperty")).addProperty(p("hasName"), r.op)
            val uomRes = m.createResource(meta.uomUri()).addProperty(RDF.type, c("UnitOfMeasurement")).addProperty(p("hasName"), r.uom)
            val resRes = m.createResource(resUri(o, r)).addProperty(RDF.type, c("Result"))
            obsRes.addProperty(p("hasResult"), resRes)
            resRes.addProperty(p("isResultByObservation"), obsRes)
            resRes.addProperty(p("hasValue"), r.valueStr)                 // xsd:string
            resRes.addProperty(p("hasObservedProperty"), opRes)
            resRes.addProperty(p("hasUnitOfMeasurement"), uomRes)

            val ipRes = m.createResource(Metadata.indexPointUri(o.mds, meta)).addProperty(RDF.type, c("IndexPoint"))
            ipRes.addProperty(p("isIndexPointByMultiDatastream"), mdsRes)
            ipRes.addProperty(p("pointToObservedProperty"), opRes)
            ipRes.addProperty(p("pointToUnitOfMeasurement"), uomRes)
            mdsRes.addProperty(p("hasIndexPoint"), ipRes)
            ipRes.addProperty(p("pointToResult"), resRes)
        }
    }

    private fun deleteObs(m: Model, o: Obs) {
        fun p(local: String) = m.createProperty(S + local)
        val mdsRes = m.createResource(mdsUri(o.mds))
        val obsRes = m.createResource(obsUri(o))
        m.removeAll(mdsRes, p("hasObservation"), obsRes)
        o.results.forEach { r ->
            val resRes = m.createResource(resUri(o, r))
            val ipRes = m.createResource(Metadata.indexPointUri(o.mds, Metadata(r.op, r.uom)))
            m.removeAll(ipRes, p("pointToResult"), resRes)
            resRes.removeProperties()
        }
        obsRes.removeProperties()
    }

    private fun materialize(m: Model, now: Long) {
        val asOf = Instant.ofEpochMilli(now).toString()
        val spec = "range=${windowMs / 1000}s;slide=${slideMs / 1000}s"
        fun p(local: String) = m.createProperty(S + local)
        agg.forEach { (uri, a) ->
            val ip = m.createResource(uri)
            ip.removeAll(p("hasWindowedCount")); ip.addLiteral(p("hasWindowedCount"), a.count)
            ip.removeAll(p("hasWindowedSum")); ip.addLiteral(p("hasWindowedSum"), a.sum)
            ip.removeAll(p("hasWindowedAverage")); ip.addLiteral(p("hasWindowedAverage"), if (a.count > 0) a.sum / a.count else 0.0)
            ip.removeAll(p("hasLatestValue")); if (a.count > 0 && a.last.isNotEmpty()) ip.addProperty(p("hasLatestValue"), a.last)
            ip.removeAll(p("windowSpec")); ip.addProperty(p("windowSpec"), spec)
            ip.removeAll(p("asOf")); ip.addProperty(p("asOf"), asOf)
        }
        lastAsOf = asOf
    }

    private inline fun timeUs(block: () -> Unit): Long { val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1000 }

    /** IndexPoint 유지 집계를 읽어 2차 융합 산출 (O(k)). */
    private fun computeIndexed(): Int =
        agg.values.filter { it.count > 0 }.groupBy { Metadata(it.op, it.uom) }.size

    /** IndexPoint 없이 전 Result를 스캔+group-by 하여 산출 (O(W)). */
    private fun computeNaive(m: Model): Int {
        val q = """
            PREFIX sta: <$S>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            SELECT ?op ?uom (COUNT(?v) AS ?c) (SUM(xsd:double(?v)) AS ?s)
            WHERE {
              ?r a sta:Result ; sta:hasValue ?v ;
                 sta:hasObservedProperty ?opr ; sta:hasUnitOfMeasurement ?ur .
              ?opr sta:hasName ?op . ?ur sta:hasName ?uom .
            } GROUP BY ?op ?uom
        """.trimIndent()
        var rows = 0
        QueryExecutionFactory.create(q, m).use { qe ->
            val rs = qe.execSelect()
            while (rs.hasNext()) { rs.next(); rows++ }
        }
        return rows
    }

    // ---- 모니터링 (2차 융합 뷰) ----
    data class IndexPointView(
        val label: String, val op: String, val uom: String,
        val cohort: Int, val count: Int, val sum: Double, val average: Double,
    )

    data class EngineSnapshot(
        val enabled: Boolean, val windowSeconds: Long, val slideSeconds: Long,
        val indexPointCount: Int, val edgeCount: Int, val residentObs: Int, val asOf: String,
        val lastMaterializeMs: Long, val indexPoints: List<IndexPointView>,
        val aggMode: String, val computeMs: Double,
    )

    fun snapshot(): EngineSnapshot = synchronized(lock) {
        val byMeta = agg.values.filter { it.count > 0 }.groupBy { Metadata(it.op, it.uom) }
        val views = byMeta.map { (meta, entries) ->
            val c = entries.sumOf { it.count }
            val s = entries.sumOf { it.sum }
            IndexPointView(meta.label(), meta.op, meta.uom,
                cohort = registry.cohortMdsCount(meta), count = c, sum = round2(s),
                average = round2(if (c > 0) s / c else 0.0))
        }.sortedByDescending { it.cohort }
        EngineSnapshot(
            enabled = enabled, windowSeconds = windowMs / 1000, slideSeconds = slideMs / 1000,
            indexPointCount = agg.count { it.value.count > 0 }, edgeCount = registry.size(),
            residentObs = resident.size, asOf = lastAsOf, lastMaterializeMs = lastMaterializeMs, indexPoints = views,
            aggMode = aggMode, computeMs = round3(computeUs / 1000.0),
        )
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
