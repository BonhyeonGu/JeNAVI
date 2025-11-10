package jenavi.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        // --- SPA fallback for non-API routes (PathPatternParser compatible) ---
        registry.addViewController("/{path:[^\\.]*}")
            .setViewName("forward:/index.html")
    }
}