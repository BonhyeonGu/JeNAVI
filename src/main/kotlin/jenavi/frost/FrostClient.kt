package jenavi.frost

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jenavi.config.OntologyProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * FROST SensorThings REST 조회 클라이언트.
 *
 * MQTT는 thin Observation 알림만 주므로, 어떤 MultiDatastream들이 있는지/그 구조는
 * REST로 따로 알아야 한다. 여기서는 MultiDatastream 열거(→ per-MDS 구독 토픽 생성)를 담당한다.
 * (향후 레지스트리: $expand로 op/uom/odt 컬럼까지 가져와 result[] 인덱스→IndexPoint URI 매핑)
 */
@Component
class FrostClient {

    private val logger = LoggerFactory.getLogger(FrostClient::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    data class MdsRef(val id: String, val name: String?, val thingName: String? = null)
    data class ThingRef(val id: String, val name: String?)
    data class MdsComp(
        val id: String, val name: String?, val thingId: String?, val thingName: String?,
        val ops: List<String>, val uoms: List<String>,
    )

    /**
     * MultiDatastream별 컴포넌트(ObservedProperty 이름 + 단위, result 인덱스 순)를 읽는다.
     * STA에서 ObservedProperties / unitOfMeasurements 배열은 result[] 인덱스와 평행. (실제 레인 레지스트리 로드용)
     */
    fun listMdsComponents(base: String = OntologyProperties.frostRestBase): List<MdsComp> {
        val out = mutableListOf<MdsComp>()
        var url: String? = "${base.trimEnd('/')}/MultiDatastreams?\$select=id,name,unitOfMeasurements" +
            "&\$expand=ObservedProperties(\$select=name),Thing(\$select=id,name)&\$top=100"
        var guard = 0
        while (url != null && guard < 10_000) {
            guard++
            val body = get(url) ?: break
            val root = mapper.readTree(body)
            root.path("value").forEach { node ->
                val id = idOf(node) ?: return@forEach
                val ops = node.path("ObservedProperties").map { it.path("name").asTextOrNull() ?: "?" }
                val uoms = node.path("unitOfMeasurements").map {
                    it.path("symbol").asTextOrNull() ?: it.path("name").asTextOrNull() ?: "?"
                }
                out.add(
                    MdsComp(
                        id = id,
                        name = node.path("name").asTextOrNull(),
                        thingId = node.path("Thing").path("@iot.id").asTextOrNull(),
                        thingName = node.path("Thing").path("name").asTextOrNull(),
                        ops = ops, uoms = uoms,
                    )
                )
            }
            url = root.path("@iot.nextLink").asTextOrNull()
        }
        logger.info("FROST listMdsComponents: {} MDS", out.size)
        return out
    }

    /** Thing 전체 열거 (정의 로드용, 읽기 전용). */
    fun listThings(base: String = OntologyProperties.frostRestBase): List<ThingRef> {
        val out = mutableListOf<ThingRef>()
        var url: String? = "${base.trimEnd('/')}/Things?\$select=id,name&\$top=100"
        var guard = 0
        while (url != null && guard < 10_000) {
            guard++
            val body = get(url) ?: break
            val root = mapper.readTree(body)
            root.path("value").forEach { node ->
                idOf(node)?.let { out.add(ThingRef(it, node.path("name").asTextOrNull())) }
            }
            url = root.path("@iot.nextLink").asTextOrNull()
        }
        logger.info("FROST listThings: {} found", out.size)
        return out
    }

    /** MultiDatastream 전체 열거 (페이징 추적, Thing 이름 포함). */
    fun listMultiDatastreams(base: String = OntologyProperties.frostRestBase): List<MdsRef> {
        val out = mutableListOf<MdsRef>()
        var url: String? =
            "${base.trimEnd('/')}/MultiDatastreams?\$select=id,name&\$expand=Thing(\$select=name)&\$top=100"
        var guard = 0
        while (url != null && guard < 10_000) {
            guard++
            val body = get(url) ?: break
            val root = mapper.readTree(body)
            root.path("value").forEach { node ->
                idOf(node)?.let {
                    out.add(MdsRef(it, node.path("name").asTextOrNull(), node.path("Thing").path("name").asTextOrNull()))
                }
            }
            url = root.path("@iot.nextLink").asTextOrNull()
        }
        logger.info("FROST listMultiDatastreams: {} found", out.size)
        return out
    }

    /** 특정 Thing에 속한 MultiDatastream 열거 (Thing→MDS 경로). */
    fun listMultiDatastreamsOfThing(thingId: String, base: String = OntologyProperties.frostRestBase): List<MdsRef> {
        val out = mutableListOf<MdsRef>()
        var url: String? = "${base.trimEnd('/')}/Things($thingId)/MultiDatastreams?\$select=id,name&\$top=100"
        var guard = 0
        while (url != null && guard < 10_000) {
            guard++
            val body = get(url) ?: break
            val root = mapper.readTree(body)
            root.path("value").forEach { node ->
                idOf(node)?.let { out.add(MdsRef(it, node.path("name").asTextOrNull())) }
            }
            url = root.path("@iot.nextLink").asTextOrNull()
        }
        return out
    }

    /** per-MDS Observation 구독 토픽 목록. */
    fun observationTopics(base: String = OntologyProperties.frostRestBase): List<String> =
        listMultiDatastreams(base).map { "v1.1/MultiDatastreams(${it.id})/Observations" }

    private fun get(url: String): String? {
        return try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() in 200..299) {
                res.body()
            } else {
                logger.warn("FROST GET {} -> HTTP {}", url, res.statusCode())
                null
            }
        } catch (e: Exception) {
            logger.warn("FROST GET {} failed: {}", url, e.message)
            null
        }
    }

    private fun idOf(node: JsonNode): String? = node.path("@iot.id").asTextOrNull()

    private fun JsonNode.asTextOrNull(): String? =
        if (isMissingNode || isNull) null else asText().takeIf { it.isNotBlank() }
}
