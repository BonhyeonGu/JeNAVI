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
}
