package jenavi.contracts

// 공용 응답 래퍼
data class ApiResponse<out T>(
    val status: String,   // "ok" | "ok (update)" | "error"
    val timeMs: Long,
    val data: T
)

// 요청 DTO
data class SparqlRequest(
    val query: String,
    val graphFormat: String? = null  // "TURTLE" | "NTRIPLES" | "JSONLD" | "RDFXML"
)

// 결과 DTO
data class TableDTO(val vars: List<String>, val rows: List<List<String>>)
data class BoolDTO(val value: Boolean)
data class GraphDTO(val rdf: String, val format: String)
object UpdateAckDTO { override fun toString() = "UpdateAck" }

// 실행 결과 타입 — 여기서 '한 번만' 정의합니다!
sealed class QueryResult {
    data class Table(val vars: List<String>, val rows: List<List<String>>) : QueryResult()
    data class Graph(val model: org.apache.jena.rdf.model.Model) : QueryResult()
    data class Bool(val value: Boolean) : QueryResult()
    object UpdateAck : QueryResult()
}
data class TimedResult(val millis: Long, val result: QueryResult)



// ----- /api/browse 전용 DTO -----
data class BrowseRequest(
    val uri: String
)

data class TripleRow(
    val property: String,
    val value: String,
    val link: String?,   // 리터럴이면 null
    val rowspan: Int
)

data class BrowsePayload(
    val resourceURI: String,
    val isProperty: Boolean,
    val propertyDetails: List<TripleRow>?, // isProperty = true
    val outgoing: List<TripleRow>?,        // isProperty = false
    val incoming: List<TripleRow>?,        // isProperty = false
    val timePropertyMs: Long?,             // isProperty = true
    val timeOutgoingMs: Long?,             // isProperty = false
    val timeIncomingMs: Long?              // isProperty = false
)

// 배치 적재 요청 (옵션: dir, 포맷, 적재 후 삭제 여부)
data class IngestRequest(
    val dir: String? = null,
    val format: String? = "RDF/XML",
    val deleteAfter: Boolean = true
)