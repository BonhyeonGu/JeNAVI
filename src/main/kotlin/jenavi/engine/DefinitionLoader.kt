package jenavi.engine

import jenavi.Ontology
import jenavi.frost.FrostClient
import org.apache.jena.rdf.model.Model
import org.apache.jena.vocabulary.RDF
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 센서 "정의부"를 온톨로지(STA 1.3 ABox)로 적재 + 레지스트리(레인의 (mds,idx)->metadata 엣지)를 채운다.
 * 레지스트리가 채워져야 WindowedReasoningEngine이 그 레인의 MDS를 resolve해 Observation을 붙일 수 있다.
 *
 * - experiment: 가짜 fleet(mds 1001..1023) 시드.
 * - real:       aidtlab FROST에서 MDS 컴포넌트(op/uom, result index 순)를 읽어 적재 — 실제 MDS id 기준.
 *
 * 레인 전환 시 registry.clear()로 초기화(스테일 엣지 방지).
 */
@Component
class DefinitionLoader(
    private val registry: MetadataRegistry,
    private val frost: FrostClient,
    private val ontology: Ontology,
) {
    private val logger = LoggerFactory.getLogger(DefinitionLoader::class.java)
    private val S = Metadata.STA

    fun loadExperiment(): Int {
        registry.clear()
        registry.seedExperimentDefaultsIfEmpty()
        val list = registry.mdsList()
        ontology.writeTx { m ->
            list.forEach { (mds, profile) ->
                buildDefs(m, thingId = mds, thingName = "EXP_${profile}_$mds",
                    mds = mds, mdsName = "EXP_${profile}_MDS_$mds", metas = registry.indexPointsOf(mds).distinct())
            }
        }
        logger.info("Experiment definitions loaded: {} MDS", list.size)
        return list.size
    }

    fun loadReal(): Int {
        registry.clear()
        val comps = frost.listMdsComponents()
        ontology.writeTx { m ->
            comps.forEach { c ->
                val metas = c.ops.indices.map { i -> Metadata(c.ops[i], c.uoms.getOrElse(i) { "?" }) }
                registry.loadEdges(c.id, metas, c.thingName ?: c.name ?: "mds${c.id}")
                buildDefs(m, thingId = c.thingId ?: "t${c.id}", thingName = c.thingName ?: c.name ?: "Thing ${c.id}",
                    mds = c.id, mdsName = c.name ?: "MDS ${c.id}", metas = metas.distinct())
            }
        }
        logger.info("Real definitions loaded: {} MDS", comps.size)
        return comps.size
    }

    /** Thing -> MultiDatastream -> per-MDS IndexPoint(+공유 op/uom 개체) 정적 정의. 레인 무관 통일 URI. */
    private fun buildDefs(m: Model, thingId: String, thingName: String, mds: String, mdsName: String, metas: List<Metadata>) {
        fun p(l: String) = m.createProperty(S + l)
        fun c(l: String) = m.createResource(S + l)

        val thing = m.createResource(Metadata.thingUri(thingId))
        thing.addProperty(RDF.type, c("Thing")).addProperty(p("hasName"), thingName)

        val mdsRes = m.createResource(Metadata.mdsUri(mds))
        mdsRes.addProperty(RDF.type, c("MultiDatastream")).addProperty(p("hasName"), mdsName)
        thing.addProperty(p("hasMultiDatastream"), mdsRes)

        metas.forEach { meta ->
            val opRes = m.createResource(meta.opUri()).addProperty(RDF.type, c("ObservedProperty")).addProperty(p("hasName"), meta.op)
            val uomRes = m.createResource(meta.uomUri()).addProperty(RDF.type, c("UnitOfMeasurement")).addProperty(p("hasName"), meta.uom)
            val ip = m.createResource(Metadata.indexPointUri(mds, meta)).addProperty(RDF.type, c("IndexPoint"))
            ip.addProperty(p("isIndexPointByMultiDatastream"), mdsRes)
            ip.addProperty(p("pointToObservedProperty"), opRes)
            ip.addProperty(p("pointToUnitOfMeasurement"), uomRes)
            mdsRes.addProperty(p("hasIndexPoint"), ip)
        }
    }
}
