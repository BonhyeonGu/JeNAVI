package jenavi.config

import jenavi.Ontology
import jenavi.Ontology.Companion.PATH_DIR_TDB
import org.apache.jena.query.Dataset
import org.apache.jena.reasoner.Reasoner
import org.apache.jena.reasoner.ReasonerRegistry
import org.apache.jena.tdb2.TDB2Factory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OntologyConfig {

    @Bean(destroyMethod = "close")
    fun jenaDataset(): Dataset? =
        if (OntologyProperties.useTDB) TDB2Factory.connectDataset(PATH_DIR_TDB) else null

    @Bean
    fun ontology(datasetProvider: ObjectProvider<Dataset>): Ontology {
        val ont = Ontology.createForStartup(OntologyProperties.useTDB)
        datasetProvider.ifAvailable { ds -> ont.attachDataset(ds) }
        return ont
    }

    // 💡 부팅 시점엔 데이터 접근 금지 — Reasoner만 “빈 상태”로 만든다
    @Bean
    fun jenaReasoner(): Reasoner {
        return when (OntologyProperties.reasonerType) {
            "RDFS"      -> ReasonerRegistry.getRDFSReasoner()
            "OWL_MICRO" -> ReasonerRegistry.getOWLMicroReasoner()
            else        -> ReasonerRegistry.getOWLMiniReasoner() // 기본
        }
    }
}
