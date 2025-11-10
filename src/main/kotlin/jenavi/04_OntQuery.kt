package jenavi
//--------------------------------------------------------------------
import jenavi.config.OntologyProperties
import jenavi.contracts.QueryResult
import jenavi.contracts.TimedResult
//--------------------------------------------------------------------
import org.slf4j.Logger
import org.slf4j.LoggerFactory
//--------------------------------------------------------------------
import org.apache.jena.ontology.OntModel
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.QueryExecutionFactory
//--------------------------------------------------------------------
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.update.*
//--------------------------------------------------------------------
import org.apache.jena.query.ReadWrite

//--------------------------------------------------------------------
import kotlin.random.Random

import org.apache.jena.system.Txn
import org.apache.jena.rdf.model.Model
import org.apache.jena.tdb2.TDB2Factory


class OntQuery(
    val modelProvider: () -> OntModel,
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
            val ds: Dataset = TDB2Factory.connectDataset(Ontology.PATH_DIR_TDB)
            try { runSparqlOnDataset(queryStr, ds) }
            finally { runCatching { ds.close() } }
        } else {
            runSparqlOnModel(queryStr, modelProvider())
        }
    }

    /** 내부: Dataset 경로 (READ/WRITE 트랜잭션 보장) */
    private fun runSparqlOnDataset(queryStr: String, dataset: Dataset): TimedResult {
        val q = try { QueryFactory.create(queryStr) } catch (_: Exception) { null }
        if (q != null) {
            // 읽기 질의는 모두 Read 트랜잭션에서 실행
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
        // 업데이트는 Write 트랜잭션에서 실행
        val upd = try { UpdateFactory.create(queryStr) } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("Unrecognized SPARQL string")
        return Txn.calculateWrite(dataset) {
            updateExec(upd, dataset)
        }
    }

    /** 내부: 인메모리 OntModel 경로 */
    private fun runSparqlOnModel(queryStr: String, model: org.apache.jena.rdf.model.Model): TimedResult {
        val q = try { QueryFactory.create(queryStr) } catch (_: Exception) { null }
        if (q != null) {
            return when {
                q.isSelectType    -> selectExecMem(queryStr, model)
                q.isAskType       -> askExecMem(queryStr, model)
                q.isConstructType -> constructExecMem(queryStr, model)
                q.isDescribeType  -> describeExecMem(queryStr, model)
                else              -> throw IllegalArgumentException("Unsupported query form")
            }
        }
        val upd = try { UpdateFactory.create(queryStr) } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("Unrecognized SPARQL string")
        return updateExecMem(upd, model)
    }

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
                val sorted = rows.sortedWith(compareBy({ if (it[2] == "x") 0 else 1 }, { it[0] }, { it[1] })).toMutableList()
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
        val model = modelProvider()                    // ← 최신 OntModel
        val query = QueryFactory.create(q)
        return QueryExecutionFactory.create(query, model).use { qexec ->
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

            val sorted = rows.sortedWith(
                compareBy({ if (it[2] == "x") 0 else 1 }, { it[0] }, { it[1] })
            ).toMutableList()

            for (i in sorted.indices) {
                val cur = sorted[i]
                val rowspan = sorted.subList(i, sorted.size).takeWhile { it[0] == cur[0] }.size
                cur[3] = rowspan.toString()
            }
            sorted
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

        val model = modelProvider()                // ← 항상 최신 OntModel
        val query = QueryFactory.create(q)
        return QueryExecutionFactory.create(query, model).use { qexec ->
            qexec.execAsk()
        }
    }


//=============================================================================================
//=============================================================================================

    // Query


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
                            n.isLiteral -> {
                                val lit = n.asLiteral()
                                // 언어/데이터타입 유지 원하면 다음과 같이:
                                // "${lit.lexicalForm}^^${lit.datatypeURI ?: ""}@${lit.language ?: ""}"
                                lit.lexicalForm
                            }
                            n.isResource -> n.asResource().uri ?: n.asResource().toString() // bnode 대응
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
            // 중요: dataset 전체에 대해 실행
            UpdateAction.execute(updateRequest, dataset)
            val end = System.currentTimeMillis()
            end - start
        }
        return TimedResult(millis, QueryResult.UpdateAck)
    }

    /** 온메모리 모델 보호용 락 (필요시 외부에서 주입 가능) */
    private val modelLock = Any()

    private fun selectExecMem(queryStr: String, model: Model): TimedResult = synchronized(modelLock) {
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
                        n.isResource -> n.asResource().uri ?: n.asResource().toString() // bnode 안전
                        else -> n.toString()
                    }
                }
            }
            val end = System.currentTimeMillis()
            TimedResult(end - start, QueryResult.Table(vars, rows))
        }
    }

    private fun askExecMem(queryStr: String, model: Model): TimedResult = synchronized(modelLock) {
        val query = QueryFactory.create(queryStr)
        QueryExecutionFactory.create(query, model).use { qexec ->
            val start = System.currentTimeMillis()
            val b = qexec.execAsk()
            val end = System.currentTimeMillis()
            TimedResult(end - start, QueryResult.Bool(b))
        }
    }

    private fun constructExecMem(queryStr: String, model: Model): TimedResult = synchronized(modelLock) {
        val query = QueryFactory.create(queryStr)
        QueryExecutionFactory.create(query, model).use { qexec ->
            val start = System.currentTimeMillis()
            val outModel = qexec.execConstruct()      // 결과 그래프
            val end = System.currentTimeMillis()
            TimedResult(end - start, QueryResult.Graph(outModel))
        }
    }

    private fun describeExecMem(queryStr: String, model: Model): TimedResult = synchronized(modelLock) {
        val query = QueryFactory.create(queryStr)
        QueryExecutionFactory.create(query, model).use { qexec ->
            val start = System.currentTimeMillis()
            val outModel = qexec.execDescribe()       // 결과 그래프
            val end = System.currentTimeMillis()
            TimedResult(end - start, QueryResult.Graph(outModel))
        }
    }

    /** 온메모리 모델에 대한 SPARQL Update */
    private fun updateExecMem(updateRequest: UpdateRequest, model: Model): TimedResult = synchronized(modelLock) {
        val start = System.currentTimeMillis()
        UpdateAction.execute(updateRequest, model)     // Dataset이 아니라 Model!
        val end = System.currentTimeMillis()
        TimedResult(end - start, QueryResult.UpdateAck)
    }


//=============================================================================================

}