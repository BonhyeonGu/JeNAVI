package jenavi.engine

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 정의부 로드 트리거. FastView(빠른시작)에서 호출.
 *   /definitions/load?source=experiment   (가짜 fleet 합성 Thing)
 *   /definitions/load?source=real          (aidtlab Thing 읽어와 적재)
 */
@RestController
@RequestMapping("/definitions")
@CrossOrigin(origins = ["*"])
class DefinitionController(private val loader: DefinitionLoader) {

    @GetMapping("/load")
    fun load(@RequestParam(defaultValue = "experiment") source: String): ResponseEntity<String> {
        val n = if (source == "real") loader.loadReal() else loader.loadExperiment()
        return ResponseEntity.ok("정의 로드 완료: source=$source, Thing ${n}개 적재")
    }
}
