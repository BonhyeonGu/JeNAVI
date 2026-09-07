package jenavi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.vocabulary.RDF
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 온톨로지 내용(자산된 트리플)을 NGSI-LD(JSON-LD graph form)로 변환·다운로드.
 *
 * 각 URI 주체(rdf:type 보유)를 NGSI-LD Entity로: literal→Property{value}, resource→Relationship{object}.
 * @context = ETSI core context + (로컬 term → 전체 URI) 매핑. 추론 노이즈 배제 위해 baseModel만 사용.
 *   GET /api/export/ngsi-ld               (전체)
 *   GET /api/export/ngsi-ld?type=Thing    (해당 타입만)
 */
@RestController
@RequestMapping("/api/export")
@CrossOrigin(origins = ["*"])
class NgsiLdExportController(private val ontology: Ontology) {
    private val logger = LoggerFactory.getLogger(NgsiLdExportController::class.java)
    private val mapper = ObjectMapper()
    private val coreContext = "https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context.jsonld"

    @GetMapping("/ngsi-ld")
    fun exportNgsiLd(@RequestParam(required = false) type: String?): ResponseEntity<ByteArray> {
        val (json, count) = ontology.readTx { m -> buildNgsiLd(m.baseModel, type) }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val fname = "jenavi-ngsi-ld" + (type?.let { "-${it.lowercase()}" } ?: "") + ".jsonld"
        logger.info("NGSI-LD export: {} entities, {} bytes (typeFilter={})", count, bytes.size, type)
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("application/ld+json")
            set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fname\"")
        }
        return ResponseEntity.ok().headers(headers).body(bytes)
    }

    private fun localName(uri: String): String {
        val i = maxOf(uri.lastIndexOf('#'), uri.lastIndexOf('/'))
        return if (i in 0 until uri.length - 1) uri.substring(i + 1) else uri
    }

    private fun buildNgsiLd(m: Model, typeFilter: String?): Pair<String, Int> {
        val terms = LinkedHashMap<String, String>()   // localName -> full URI (@context)
        val graph = mapper.createArrayNode()

        val subjects = m.listSubjects()
        while (subjects.hasNext()) {
            val s = subjects.next()
            if (!s.isURIResource) continue

            val typeUris = ArrayList<String>()
            m.listObjectsOfProperty(s, RDF.type).forEach { if (it.isURIResource) typeUris.add(it.asResource().uri) }
            if (typeUris.isEmpty()) continue
            val typeLocals = typeUris.map { localName(it).also { ln -> terms.putIfAbsent(ln, it) } }
            if (typeFilter != null && typeLocals.none { it.equals(typeFilter, ignoreCase = true) }) continue

            val entity = mapper.createObjectNode()
            entity.put("id", s.uri)
            if (typeLocals.size == 1) entity.put("type", typeLocals[0])
            else entity.putArray("type").also { arr -> typeLocals.forEach { arr.add(it) } }

            // predicate -> objects (multi-valued 대비)
            val byPred = LinkedHashMap<Property, MutableList<RDFNode>>()
            val stmts = m.listStatements(s, null, null as RDFNode?)
            while (stmts.hasNext()) {
                val st = stmts.next()
                if (st.predicate == RDF.type) continue
                byPred.getOrPut(st.predicate) { mutableListOf() }.add(st.`object`)
            }
            byPred.forEach { (pred, objs) ->
                val ln = localName(pred.uri).also { terms.putIfAbsent(it, pred.uri) }
                if (objs.size == 1) {
                    entity.set<JsonNode>(ln, nodeFor(objs[0]))
                } else {
                    val arr = mapper.createArrayNode()
                    objs.forEach { arr.add(nodeFor(it)) }
                    entity.set<JsonNode>(ln, arr)
                }
            }
            graph.add(entity)
        }

        val ctxArr = mapper.createArrayNode()
        ctxArr.add(coreContext)
        val ctxObj = mapper.createObjectNode()
        terms.toSortedMap().forEach { (k, v) -> ctxObj.put(k, v) }
        ctxArr.add(ctxObj)

        val root = mapper.createObjectNode()
        root.set<JsonNode>("@context", ctxArr)
        root.set<JsonNode>("@graph", graph)
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) to graph.size()
    }

    private fun nodeFor(o: RDFNode): ObjectNode {
        val n = mapper.createObjectNode()
        when {
            o.isLiteral -> {
                n.put("type", "Property")
                val lit = o.asLiteral()
                val dt = lit.datatypeURI
                val lex = lit.lexicalForm
                when {
                    dt == null -> n.put("value", lex)
                    dt.endsWith("#int") || dt.endsWith("#integer") || dt.endsWith("#long") || dt.endsWith("#short") ->
                        lex.toLongOrNull()?.let { n.put("value", it) } ?: n.put("value", lex)
                    dt.endsWith("#double") || dt.endsWith("#float") || dt.endsWith("#decimal") ->
                        lex.toDoubleOrNull()?.let { n.put("value", it) } ?: n.put("value", lex)
                    dt.endsWith("#boolean") -> n.put("value", lex.equals("true", true))
                    else -> n.put("value", lex)
                }
            }
            o.isURIResource -> {
                n.put("type", "Relationship")
                n.put("object", o.asResource().uri)
            }
            else -> {   // blank node
                n.put("type", "Property")
                n.put("value", o.toString())
            }
        }
        return n
    }
}
