package jenavi.engine

import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.RDF
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 공정한 격리 벤치마크: IndexPoint 유지값 읽기 O(k) vs 전 Result 스캔+group-by O(W).
 *
 * 라이브 엔진과 분리 — W마다 **신선한 plain 모델**을 새로 만들어(캐시/추론/순서 오염 배제),
 * 워밍업 후 반복 측정의 **중앙값(median)** 을 기록. 두 방식은 동일한 k개 집계를 산출한다.
 * 두 질의는 각각 자기 워밍업/반복을 가지므로 상호 캐시 오염이 없다.
 */
@Component
class ExperimentRunner {
    private val logger = LoggerFactory.getLogger(ExperimentRunner::class.java)
    private val S = Metadata.STA

    data class Row(
        val w: Int, val k: Int,
        val naiveQueryMs: Double,   // O(W) 스캔+group-by 중앙값 (ms)
        val indexedQueryUs: Double, // O(k) 유지값 읽기 중앙값 (µs; ms로는 0에 반올림돼 µs로 보고)
        val speedup: Double,        // naive(ms) / indexed(ms)
        val buildMs: Double,        // 참고: 데이터 적재(양쪽 공통)
    )
    data class Report(val reps: Int, val warmup: Int, val rows: List<Row>)

    fun run(wList: List<Int>, reps: Int, warmup: Int, k: Int): Report =
        Report(reps, warmup, wList.map { runOne(it, reps, warmup, k) })

    private fun runOne(w: Int, reps: Int, warmup: Int, k: Int): Row {
        val model = ModelFactory.createDefaultModel()
        val agg = HashMap<Int, DoubleArray>() // group -> [sum, count]

        val typeResult = model.createResource(S + "Result")
        val typeOp = model.createResource(S + "ObservedProperty")
        val typeUom = model.createResource(S + "UnitOfMeasurement")
        val hasValue = model.createProperty(S + "hasValue")
        val hasOp = model.createProperty(S + "hasObservedProperty")
        val hasUom = model.createProperty(S + "hasUnitOfMeasurement")
        val hasName = model.createProperty(S + "hasName")

        val ops = (0 until k).map { g -> model.createResource("${S}exp/bench/op$g").addProperty(RDF.type, typeOp).addProperty(hasName, "op$g") }
        val uoms = (0 until k).map { g -> model.createResource("${S}exp/bench/uom$g").addProperty(RDF.type, typeUom).addProperty(hasName, "u$g") }

        val buildNs = System.nanoTime()
        for (i in 0 until w) {
            val g = i % k
            val v = (i % 100).toDouble()
            model.createResource("${S}exp/bench/r$i")
                .addProperty(RDF.type, typeResult)
                .addProperty(hasValue, v.toString())
                .addProperty(hasOp, ops[g])
                .addProperty(hasUom, uoms[g])
            agg.getOrPut(g) { DoubleArray(2) }.also { it[0] += v; it[1]++ }
        }
        val buildMs = (System.nanoTime() - buildNs) / 1_000_000.0

        val q = """
            PREFIX sta: <$S>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            SELECT ?op ?uom (COUNT(?v) AS ?c) (SUM(xsd:double(?v)) AS ?s)
            WHERE { ?r a sta:Result ; sta:hasValue ?v ; sta:hasObservedProperty ?opr ; sta:hasUnitOfMeasurement ?ur .
                    ?opr sta:hasName ?op . ?ur sta:hasName ?uom . } GROUP BY ?op ?uom
        """.trimIndent()

        fun naive(): Int {
            var n = 0
            QueryExecutionFactory.create(q, model).use { qe ->
                val rs = qe.execSelect(); while (rs.hasNext()) { rs.next(); n++ }
            }
            return n
        }
        // O(k): 유지된 sum/count에서 avg 산출
        fun indexed(): Int {
            var n = 0
            for (e in agg.values) { @Suppress("UNUSED_VARIABLE") val avg = if (e[1] > 0) e[0] / e[1] else 0.0; n++ }
            return n
        }

        repeat(warmup) { naive(); indexed() }
        // naive: 단일 호출 ms. indexed: sub-µs라 배치(inner)로 측정해 per-call µs.
        val naiveMs = DoubleArray(reps) { val t = System.nanoTime(); naive(); (System.nanoTime() - t) / 1e6 }.also { it.sort() }
        val inner = 2000
        val idxUs = DoubleArray(reps) { val t = System.nanoTime(); repeat(inner) { indexed() }; (System.nanoTime() - t) / 1e3 / inner }.also { it.sort() }
        val nMed = naiveMs[reps / 2]
        val iUsMed = idxUs[reps / 2]
        val sp = if (iUsMed > 0) nMed / (iUsMed / 1000.0) else -1.0
        logger.info("bench W={} k={}: naive={}ms indexed={}us speedup={}", w, k, nMed, iUsMed, sp)
        return Row(w, k, round3(nMed), round3(iUsMed), if (sp > 0) round2(sp) else -1.0, round3(buildMs))
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
}
