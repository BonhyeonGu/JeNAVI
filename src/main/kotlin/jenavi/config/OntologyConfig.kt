package jenavi.config

import jenavi.ObservationCleaner
import jenavi.Ontology
import org.apache.jena.ontology.OntModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.apache.jena.query.Dataset
import org.apache.jena.tdb2.TDB2Factory
import org.apache.jena.query.DatasetFactory

@Configuration
class OntologyConfig {

    @Bean
    fun ontologyModel(): OntModel {
        val useTDB = OntologyProperties.useTDB
        val ontology = Ontology.createOntology(useTDB)
        return ontology.ontologyModel
    }

    @Bean
    fun observationCleaner(): ObservationCleaner {
        val useTDB = OntologyProperties.useTDB
        val datasetProvider: () -> Dataset = {
            if (useTDB) TDB2Factory.connectDataset("./_TDB")
            else DatasetFactory.createTxnMem()
        }

        return ObservationCleaner(
            datasetProvider = datasetProvider,
            namespace = "http://paper.9bon.org/ontologies/sensorthings/1.1.3#",
            thresholdHours = OntologyProperties.thresholdHours
        )
    }
}
