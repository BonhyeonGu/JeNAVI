package jenavi.engine

import jenavi.frost.FrostClient
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.RDF
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 삽입 비용 실험 (§11 실험1 트윈-측 조각).
 *
 * Observation 서브그래프를 STA 1.3 구조대로 N개 붙이며, 누적 크기(m)가 커질 때
 * 삽입 시간이 오르는지 **배치별로** 측정한다. WindowedReasoningEngine.insertObs와
 * 동일한 서브그래프(관측→결과→IndexPoint)라 현실적이다.
 *
 * - **격리 모델**(신선한 plain Model)에 삽입 → 앱 온톨로지 오염 없음.
 * - 템플릿 = FROST MDS 몇 개(읽기 전용 조회). 실패 시 레지스트리 시드로 폴백.
 * - 곡선이 평평하면 = 삽입이 m과 무관(O(Δ) 설계 확인). 우상향이면 = 무거워질수록 느림.
 */
@Component
class InsertionExperimentRunner(
    private val registry: MetadataRegistry,
    private val frost: FrostClient,
) {
    private val logger = LoggerFactory.getLogger(InsertionExperimentRunner::class.java)
    private val S = Metadata.STA

    data class Tmpl(val mds: String, val comps: List<Metadata>)
    data class Row(
        val upTo: Int,          // 이 배치까지 누적 관측 수
        val batchMs: Double,    // 이 배치 삽입 소요 (ms)
        val cumMs: Double,      // 시작부터 누적 (ms)
        val perObsUs: Double,   // 이 배치 관측당 (µs)
        val triples: Long,      // 이 시점 모델의 트리플 수 (m)
    )
    data class Report(
        val n: Int, val batch: Int, val templateSource: String,
        val templateCount: Int, val resultsPerObs: Double,
        val rows: List<Row>, val totalMs: Double, val finalTriples: Long,
    )

    /** 템플릿 확보: FROST 우선(읽기 전용), 실패 시 레지스트리 시드. */
    fun templates(source: String, want: Int): Pair<String, List<Tmpl>> {
        val w = want.coerceIn(1, 50)
        if (source != "seed") {
            runCatching {
                val comps = frost.listMdsComponents()
                val t = comps.take(w).map { c ->
                    val n = minOf(c.ops.size, c.uoms.size)
                    Tmpl(c.id, (0 until n).map { Metadata(c.ops[it], c.uoms[it]) })
                }.filter { it.comps.isNotEmpty() }
                if (t.isNotEmpty()) return "frost" to t
            }.onFailure { logger.warn("FROST templates failed, fallback to seed: {}", it.message) }
        }
        registry.seedExperimentDefaultsIfEmpty()
        val t = registry.mdsList().take(w)
            .map { (mds, _) -> Tmpl(mds, registry.indexPointsOf(mds)) }
            .filter { it.comps.isNotEmpty() }
        return "seed" to t
    }

    fun run(n: Int, batch: Int, source: String, templateCount: Int): Report {
        val (src, tmpls) = templates(source, templateCount)
        require(tmpls.isNotEmpty()) { "no templates available (frost/seed both empty)" }
        val b = batch.coerceIn(1, n.coerceAtLeast(1))
        val avgResults = tmpls.map { it.comps.size }.average()

        // JIT 워밍업(측정 오염 배제): throwaway 모델에 소량 선삽입.
        val warm = ModelFactory.createDefaultModel()
        for (i in 0 until minOf(500, n)) insertObs(warm, tmpls[i % tmpls.size], i)

        val model = ModelFactory.createDefaultModel()
        val rows = ArrayList<Row>()
        val startNs = System.nanoTime()
        var batchStartNs = startNs
        var i = 0
        while (i < n) {
            insertObs(model, tmpls[i % tmpls.size], i)
            i++
            if (i % b == 0 || i == n) {
                val nowNs = System.nanoTime()
                val bMs = (nowNs - batchStartNs) / 1e6
                val cnt = ((i - 1) % b) + 1
                rows.add(Row(i, round3(bMs), round3((nowNs - startNs) / 1e6),
                    round3(bMs * 1000.0 / cnt), model.size()))
                batchStartNs = nowNs
            }
        }
        val totalMs = (System.nanoTime() - startNs) / 1e6
        logger.info("insertion exp: n={} src={} tmpls={} total={}ms triples={}", n, src, tmpls.size, totalMs, model.size())
        return Report(n, b, src, tmpls.size, round3(avgResults), rows, round3(totalMs), model.size())
    }

    /** WindowedReasoningEngine.insertObs와 동일한 STA 1.3 서브그래프. */
    private fun insertObs(m: Model, t: Tmpl, i: Int) {
        fun p(local: String) = m.createProperty(S + local)
        fun c(local: String) = m.createResource(S + local)
        val obsId = "e$i"
        val mdsRes = m.createResource(Metadata.mdsUri(t.mds))
        val obsRes = m.createResource(Metadata.obsUri(t.mds, obsId))
        // 결정적 시간(Date.now 미사용): 재현성 위해 i 기반.
        val ptLit = m.createTypedLiteral(Instant.ofEpochMilli(1_600_000_000_000L + i).toString(), XSDDatatype.XSDdateTimeStamp)
        obsRes.addProperty(RDF.type, c("Observation"))
        mdsRes.addProperty(p("hasObservation"), obsRes)
        obsRes.addProperty(p("isObservationOfMultiDatastream"), mdsRes)
        obsRes.addProperty(p("hasPhenomenonTime"), ptLit)
        obsRes.addProperty(p("hasResultTime"), ptLit)
        t.comps.forEachIndexed { idx, meta ->
            val v = ((i + idx) % 100).toString()
            val opRes = m.createResource(meta.opUri()).addProperty(RDF.type, c("ObservedProperty")).addProperty(p("hasName"), meta.op)
            val uomRes = m.createResource(meta.uomUri()).addProperty(RDF.type, c("UnitOfMeasurement")).addProperty(p("hasName"), meta.uom)
            val resRes = m.createResource(Metadata.resUri(t.mds, obsId, idx)).addProperty(RDF.type, c("Result"))
            obsRes.addProperty(p("hasResult"), resRes)
            resRes.addProperty(p("isResultByObservation"), obsRes)
            resRes.addProperty(p("hasValue"), v)
            resRes.addProperty(p("hasObservedProperty"), opRes)
            resRes.addProperty(p("hasUnitOfMeasurement"), uomRes)
            val ipRes = m.createResource(Metadata.indexPointUri(t.mds, meta)).addProperty(RDF.type, c("IndexPoint"))
            ipRes.addProperty(p("isIndexPointByMultiDatastream"), mdsRes)
            ipRes.addProperty(p("pointToObservedProperty"), opRes)
            ipRes.addProperty(p("pointToUnitOfMeasurement"), uomRes)
            mdsRes.addProperty(p("hasIndexPoint"), ipRes)
            ipRes.addProperty(p("pointToResult"), resRes)
        }
    }

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
}
