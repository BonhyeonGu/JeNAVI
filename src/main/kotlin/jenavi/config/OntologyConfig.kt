package jenavi.config

import jenavi.Ontology
import org.apache.jena.ontology.OntModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OntologyConfig {

    @Bean
    fun ontologyModel(): OntModel {
        val useTDB = OntologyProperties.useTDB
        val ontology = Ontology.createOntology(useTDB)
        return ontology.ontologyModel
    }
}
