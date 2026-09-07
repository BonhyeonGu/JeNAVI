package jenavi.engine

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 공유 메타데이터 개체 (op, uom). 이종 MDS라도 같은 (op, uom)이면 동일 ObservedProperty/UoM 개체를
 * 참조한다(uniform individual identification). 2차 융합의 그룹 키.
 */
data class Metadata(val op: String, val uom: String) {
    fun label(): String = "$op/$uom"
    fun opUri(): String = STA + "ObservedProperty/" + san(op)
    fun uomUri(): String = STA + "UnitOfMeasurement/" + san(uom)

    companion object {
        const val STA = "https://paper.9bon.org/ontologies/sensorthings/1.3#"
        fun san(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

        // 레인 무관 통일 URI 스킴 (mds id 기반; 실험 1001.. / 실제 FROST id 는 안 겹침)
        fun thingUri(id: String) = "${STA}Thing/$id"
        fun mdsUri(mds: String) = "${STA}MultiDatastream/$mds"
        fun obsUri(mds: String, obsId: String) = "${STA}Observation/$mds/$obsId"
        fun resUri(mds: String, obsId: String, i: Int) = "${STA}Result/$mds/$obsId/$i"

        /** IndexPoint는 OWL상 (MultiDatastream, metadata) 단위 = per-MDS. */
        fun indexPointUri(mds: String, meta: Metadata): String =
            "${STA}IndexPoint/$mds/${san(meta.op)}_${san(meta.uom)}"
    }
}

/**
 * 비정상(non-stationary) 파티션 자료구조.
 * edge: (mdsId, resultIndex) -> Metadata. 런타임 insert/delete/reroute가 이 연구의 심장.
 * (단위 드리프트 = reroute = delete + insert.)
 */
@Component
class MetadataRegistry {
    private val logger = LoggerFactory.getLogger(MetadataRegistry::class.java)
    private val edges = ConcurrentHashMap<Pair<String, Int>, Metadata>()
    private val mdsProfile = ConcurrentHashMap<String, String>()  // mdsId -> profile (실험용)

    fun resolve(mdsId: String, idx: Int): Metadata? = edges[mdsId to idx]

    fun insertEdge(mdsId: String, idx: Int, meta: Metadata) { edges[mdsId to idx] = meta }
    fun deleteEdge(mdsId: String, idx: Int) { edges.remove(mdsId to idx) }
    fun rerouteEdge(mdsId: String, idx: Int, newMeta: Metadata) { edges[mdsId to idx] = newMeta }

    /** 레인 전환 시 초기화. */
    @Synchronized
    fun clear() { edges.clear(); mdsProfile.clear() }

    /** 한 MDS의 컴포넌트(op/uom, result index 순)를 통째로 적재 (실제 FROST 로드용). */
    @Synchronized
    fun loadEdges(mdsId: String, metas: List<Metadata>, profile: String) {
        mdsProfile[mdsId] = profile
        metas.forEachIndexed { i, m -> edges[mdsId to i] = m }
    }

    fun size(): Int = edges.size
    fun isEmpty(): Boolean = edges.isEmpty()

    /** 이 metadata를 공급하는 서로 다른 MDS 수 = 2차 융합 코호트 크기. */
    fun cohortMdsCount(meta: Metadata): Int =
        edges.entries.asSequence().filter { it.value == meta }.map { it.key.first }.distinct().count()

    /** 구별되는 metadata(op,uom) 수 = k. */
    fun distinctMetaCount(): Int = edges.values.distinct().size

    /** 시드된 (mdsId, profile) 목록 (mds 오름차순). */
    fun mdsList(): List<Pair<String, String>> =
        mdsProfile.entries.map { it.key to it.value }.sortedBy { it.first.toIntOrNull() ?: 0 }

    fun profileOf(mds: String): String? = mdsProfile[mds]

    /** 이 mds가 공급하는 metadata들(result index 순). */
    fun indexPointsOf(mds: String): List<Metadata> =
        edges.entries.filter { it.key.first == mds }.sortedBy { it.key.second }.map { it.value }

    @Synchronized
    fun seedExperimentDefaultsIfEmpty() {
        if (edges.isNotEmpty()) return

        // 교차기종 융합을 위해 temperature/humidity/pressure/pm* 는 프로필 간 통일.
        val occupancy = listOf("occupancy" to "people", "entry" to "people", "exit_count" to "people")
        val wht = listOf(
            "temperature" to "°C", "humidity" to "%", "pressure" to "hPa",
            "pm1.0" to "ug/m3", "pm2.5" to "ug/m3", "pm10" to "ug/m3",
            "co" to "ppm", "tvoc" to "ppb", "vibration" to "TVI", "quake" to "R",
        )
        val oaq = listOf(
            "pm1.0" to "ug/m3", "pm2.5" to "ug/m3", "pm10" to "ug/m3",
            "temperature" to "°C", "humidity" to "%", "pressure" to "hPa", "coci" to "idx",
        )
        val solar = listOf(
            "output_power" to "kW", "input_power" to "kW", "today_generation" to "kWh",
            "temperature" to "°C", "humidity" to "%",
        )

        fun seed(mdsRange: IntRange, profile: String, comps: List<Pair<String, String>>) {
            for (mds in mdsRange) {
                mdsProfile[mds.toString()] = profile
                comps.forEachIndexed { i, (op, uom) -> edges[mds.toString() to i] = Metadata(op, uom) }
            }
        }
        seed(1001..1010, "occupancy", occupancy)
        seed(1011..1015, "wht", wht)
        seed(1016..1020, "oaq", oaq)
        seed(1021..1023, "solar", solar)

        logger.info("MetadataRegistry seeded: {} edges, {} distinct metadata", edges.size, edges.values.distinct().size)
    }
}
