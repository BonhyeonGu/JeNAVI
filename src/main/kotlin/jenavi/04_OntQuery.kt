package jenavi
//--------------------------------------------------------------------
import jenavi.config.OntologyProperties
import jenavi.contracts.QueryResult
import jenavi.contracts.TimedResult
//--------------------------------------------------------------------
import org.slf4j.Logger
import org.slf4j.LoggerFactory
//--------------------------------------------------------------------
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.Dataset
import org.apache.jena.rdf.model.Model
import org.apache.jena.system.Txn
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.update.UpdateAction
import org.apache.jena.update.UpdateFactory
import org.apache.jena.update.UpdateRequest
//--------------------------------------------------------------------

class OntQuery(
    private val ontology: Ontology,
    val cache: Boolean
) {
    private val queries: MutableMap<String, String> = mutableMapOf()

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(OntQuery::class.java)

        // 필요 시 끄고 켜기 — 서비스 성격상 기본 ON 권장
        private const val ENABLE_INFERENCE: Boolean = true

        init {
            org.apache.jena.geosparql.configuration.GeoSPARQLConfig.setupMemoryIndex()
        }
    }

    fun runSparql(queryStr: String): TimedResult {
        return if (OntologyProperties.useTDB) {
            // ✅ TDB2: 기존처럼 Dataset + Txn 경로 유지 (안전)
            val ds: Dataset = TDB2Factory.connectDataset(Ontology.PATH_DIR_TDB)
            try {
                runSparqlOnDataset(queryStr, ds)
            } finally {
                runCatching { ds.close() }
            }
        } else {
            // ✅ in-memory: 반드시 Ontology.readTx/writeTx 아래에서만 실행
            runSparqlOnInMemoryWithOntologyLock(queryStr)
        }
    }

    // =============================================================================================
    // TDB2 경로 (기존 구조 유지)
    // =============================================================================================

    /** 내부: Dataset 경로 (READ/WRITE 트랜잭션 보장) */
    private fun runSparqlOnDataset(queryStr: String, dataset: Dataset): TimedResult {
        val q = try { QueryFactory.create(queryStr) } catch (_: Exception) { null }
        if (q != null) {
            return Txn.calculateRead(dataset) {
                when {
                    q.isSelectType    -> selectExec(queryStr, dataset)
                    q.isAskType       -> askExec(queryStr, dataset)
                    q.isConstructType -> constructExec(queryStr, dataset)
                    q.isDescribeType  -> describeExec(queryStr, dataset)
                    else              -> throw IllegalArgumentException("Unsupported query form")
                }
            }
        }

        val upd = try { UpdateFactory.create(queryStr) } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("Unrecognized SPARQL string")

        return Txn.calculateWrite(dataset) {
            updateExec(upd, dataset)
        }
    }

    // =============================================================================================
    // in-memory 경로 (핵심 수정: modelProvider 제거 + Ontology lock 사용)
    // =============================================================================================

    private fun runSparqlOnInMemoryWithOntologyLock(queryStr: String): TimedResult {
        val q = try { QueryFactory.create(queryStr) } catch (_: Exception) { null }
        if (q != null) {
            return ontology.readTx { txModel ->
                // txModel은 OntModel이지만 Model 인터페이스로 사용 가능
                when {
                    q.isSelectType    -> selectExecMem(queryStr, txModel)
                    q.isAskType       -> askExecMem(queryStr, txModel)
                    q.isConstructType -> constructExecMem(queryStr, txModel)
                    q.isDescribeType  -> describeExecMem(queryStr, txModel)
                    else              -> throw IllegalArgumentException("Unsupported query form")
                }
            }
        }

        val upd = try { UpdateFactory.create(queryStr) } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("Unrecognized SPARQL string")

        return ontology.writeTx { txModel ->
            updateExecMem(upd, txModel)
        }
    }

    // =============================================================================================
    // Browse / isProperty (in-memory도 반드시 readTx로)
    // =============================================================================================

    fun browseQuery(q: String, dataset: Dataset): List<Array<String>> {
        return Txn.calculateRead(dataset) {
            val query = QueryFactory.create(q)
            QueryExecutionFactory.create(query, dataset).use { qexec ->
                val results = qexec.execSelect()
                val rows = mutableListOf<Array<String>>()
                while (results.hasNext()) {
                    val soln = results.nextSolution()
                    val property = soln.getResource("property").toString()
                    val v = soln.get("value")
                    val (text, link) =
                        if (v != null && v.isResource) {
                            val uri = soln.getResource("value").toString()
                            uri to uri
                        } else {
                            val lit = soln.getLiteral("value")?.toString() ?: ""
                            lit to "x"
                        }
                    rows.add(arrayOf(property, text, link, ""))
                }

                val sorted = rows
                    .sortedWith(compareBy({ if (it[2] == "x") 0 else 1 }, { it[0] }, { it[1] }))
                    .toMutableList()

                for (i in sorted.indices) {
                    val cur = sorted[i]
                    val rowspan = sorted.subList(i, sorted.size).takeWhile { it[0] == cur[0] }.size
                    cur[3] = rowspan.toString()
                }
                sorted
            }
        }
    }

    fun browseQuery(q: String): List<Array<String>> {
        return ontology.readTx { model ->
            val query = QueryFactory.create(q)
            QueryExecutionFactory.create(query, model).use { qexec ->
                val results = qexec.execSelect()
                val rows = mutableListOf<Array<String>>()

                while (results.hasNext()) {
                    val soln = results.nextSolution()
                    val property = soln.getResource("property").toString()
                    val v = soln.get("value")
                    val (text, link) =
                        if (v != null && v.isResource) {
                            val uri = soln.getResource("value").toString()
                            uri to uri
                        } else {
                            val lit = soln.getLiteral("value")?.toString() ?: ""
                            lit to "x"
                        }
                    rows.add(arrayOf(property, text, link, ""))
                }

                val sorted = rows
                    .sortedWith(compareBy({ if (it[2] == "x") 0 else 1 }, { it[0] }, { it[1] }))
                    .toMutableList()

                for (i in sorted.indices) {
                    val cur = sorted[i]
                    val rowspan = sorted.subList(i, sorted.size).takeWhile { it[0] == cur[0] }.size
                    cur[3] = rowspan.toString()
                }
                sorted
            }
        }
    }

    fun isProperty(resourceURI: String, dataset: Dataset): Boolean {
        val q = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        ASK WHERE {
            {<$resourceURI> a rdf:Property.}
            UNION {<$resourceURI> a owl:ObjectProperty.}
            UNION {<$resourceURI> a owl:DatatypeProperty.}
        }
    """.trimIndent()

        return Txn.calculateRead(dataset) {
            val query = QueryFactory.create(q)
            QueryExecutionFactory.create(query, dataset).use { qexec ->
                qexec.execAsk()
            }
        }
    }

    fun isProperty(resourceURI: String): Boolean {
        val q = """
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        ASK WHERE {
            {<$resourceURI> a rdf:Property.}
            UNION
            {<$resourceURI> a owl:ObjectProperty.}
            UNION
            {<$resourceURI> a owl:DatatypeProperty.}
        }
    """.trimIndent()

        return ontology.readTx { model ->
            val query = QueryFactory.create(q)
            QueryExecutionFactory.create(query, model).use { qexec ->
                qexec.execAsk()
            }
        }
    }

    // =============================================================================================
    // Dataset 기반 Query (기존 그대로)
    // =============================================================================================

    private fun selectExec(queryStr: String, dataset: Dataset): TimedResult {
        return Txn.calculateRead(dataset) {
            val query = QueryFactory.create(queryStr)
            QueryExecutionFactory.create(query, dataset).use { qexec ->
                val start = System.currentTimeMillis()
                val rs = qexec.execSelect()
                val vars = rs.resultVars
                val rows = mutableListOf<List<String>>()
                while (rs.hasNext()) {
                    val qs = rs.nextSolution()
                    rows += vars.map { v ->
                        val n = qs.get(v)
                        when {
                            n == null -> "null"
                            n.isLiteral -> n.asLiteral().lexicalForm
                            n.isResource -> n.asResource().uri ?: n.asResource().toString()
                            else -> n.toString()
                        }
                    }
                }
                val end = System.currentTimeMillis()
                TimedResult(end - start, QueryResult.Table(vars, rows))
            }
        }
    }

    private fun askExec(queryStr: String, dataset: Dataset): TimedResult {
        return Txn.calculateRead(dataset) {
            val query = QueryFactory.create(queryStr)
            QueryExecutionFactory.create(query, dataset).use { qexec ->
                val start = System.currentTimeMillis()
                val b = qexec.execAsk()
                val end = System.currentTimeMillis()
                TimedResult(end - start, QueryResult.Bool(b))
            }
        }
    }

    private fun constructExec(queryStr: String, dataset: Dataset): TimedResult {
        return Txn.calculateRead(dataset) {
            val query = QueryFactory.create(queryStr)
            QueryExecutionFactory.create(query, dataset).use { qexec ->
                val start = System.currentTimeMillis()
                val model: Model = qexec.execConstruct()
                val end = System.currentTimeMillis()
                TimedResult(end - start, QueryResult.Graph(model))
            }
        }
    }

    private fun describeExec(queryStr: String, dataset: Dataset): TimedResult {
        return Txn.calculateRead(dataset) {
            val query = QueryFactory.create(queryStr)
            QueryExecutionFactory.create(query, dataset).use { qexec ->
                val start = System.currentTimeMillis()
                val model: Model = qexec.execDescribe()
                val end = System.currentTimeMillis()
                TimedResult(end - start, QueryResult.Graph(model))
            }
        }
    }

    private fun updateExec(updateRequest: UpdateRequest, dataset: Dataset): TimedResult {
        val millis = Txn.calculateWrite(dataset) {
            val start = System.currentTimeMillis()
            UpdateAction.execute(updateRequest, dataset)
            val end = System.currentTimeMillis()
            end - start
        }
        return TimedResult(millis, QueryResult.UpdateAck)
    }

    // =============================================================================================
    // in-memory Query (중요: synchronized(modelLock) 제거! Ontology.readTx/writeTx가 보호)
    // =============================================================================================

    private fun selectExecMem(queryStr: String, model: Model): TimedResult {
        val query = QueryFactory.create(queryStr)
        QueryExecutionFactory.create(query, model).use { qexec ->
            val start = System.currentTimeMillis()
            val rs = qexec.execSelect()
            val vars = rs.resultVars
            val rows = mutableListOf<List<String>>()
            while (rs.hasNext()) {
                val qs = rs.nextSolution()
                rows += vars.map { v ->
                    val n = qs.get(v)
                    when {
                        n == null -> "null"
                        n.isLiteral -> n.asLiteral().lexicalForm
                        n.isResource -> n.asResource().uri ?: n.asResource().toString()
                        else -> n.toString()
                    }
                }
            }
            val end = System.currentTimeMillis()
            return TimedResult(end - start, QueryResult.Table(vars, rows))
        }
    }

    private fun askExecMem(queryStr: String, model: Model): TimedResult {
        val query = QueryFactory.create(queryStr)
        QueryExecutionFactory.create(query, model).use { qexec ->
            val start = System.currentTimeMillis()
            val b = qexec.execAsk()
            val end = System.currentTimeMillis()
            return TimedResult(end - start, QueryResult.Bool(b))
        }
    }

    private fun constructExecMem(queryStr: String, model: Model): TimedResult {
        val query = QueryFactory.create(queryStr)
        QueryExecutionFactory.create(query, model).use { qexec ->
            val start = System.currentTimeMillis()
            val outModel = qexec.execConstruct()
            val end = System.currentTimeMillis()
            return TimedResult(end - start, QueryResult.Graph(outModel))
        }
    }

    private fun describeExecMem(queryStr: String, model: Model): TimedResult {
        val query = QueryFactory.create(queryStr)
        QueryExecutionFactory.create(query, model).use { qexec ->
            val start = System.currentTimeMillis()
            val outModel = qexec.execDescribe()
            val end = System.currentTimeMillis()
            return TimedResult(end - start, QueryResult.Graph(outModel))
        }
    }

    private fun updateExecMem(updateRequest: UpdateRequest, model: Model): TimedResult {
        val start = System.currentTimeMillis()
        UpdateAction.execute(updateRequest, model)
        val end = System.currentTimeMillis()
        return TimedResult(end - start, QueryResult.UpdateAck)
    }
}
