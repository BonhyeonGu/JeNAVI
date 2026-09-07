package jenavi.engine

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 격리 벤치마크 실행. 프론트 /experiment 뷰에서 호출.
 *   /experiment/run?w=200,500,1000,2000,4000,8000&reps=21&warmup=5&k=10
 */
@RestController
@RequestMapping("/experiment")
@CrossOrigin(origins = ["*"])
class ExperimentController(
    private val runner: ExperimentRunner,
    private val registry: MetadataRegistry,
    private val insertion: InsertionExperimentRunner,
) {

    /** 삽입 비용 실험: N개 Observation 서브그래프를 붙이며 배치별 소요시간 측정. */
    @GetMapping("/insert")
    fun insert(
        @RequestParam(defaultValue = "10000") n: Int,
        @RequestParam(defaultValue = "1000") batch: Int,
        @RequestParam(defaultValue = "frost") source: String,
        @RequestParam(defaultValue = "5") templates: Int,
    ): ResponseEntity<InsertionExperimentRunner.Report> =
        ResponseEntity.ok(insertion.run(n.coerceIn(1, 5_000_000), batch, source, templates))

    /** 사용 가능한 템플릿(FROST MDS 몇 개 또는 시드) 미리보기. */
    @GetMapping("/insert/templates")
    fun insertTemplates(
        @RequestParam(defaultValue = "frost") source: String,
        @RequestParam(defaultValue = "5") templates: Int,
    ): ResponseEntity<Map<String, Any>> {
        val (src, tmpls) = insertion.templates(source, templates)
        return ResponseEntity.ok(
            mapOf(
                "source" to src,
                "templates" to tmpls.map { mapOf("mds" to it.mds, "components" to it.comps.map { c -> c.label() }) },
            )
        )
    }

    /** 현재 로드된 레인(실험/aidtlab 무관)의 규모에서 W 스윕과 k를 추천. */
    @GetMapping("/suggest")
    fun suggest(): ResponseEntity<Map<String, Any>> {
        val base = maxOf(registry.size(), 50)           // 로드된 result 컬럼 수(전 센서 1틱)
        val k = maxOf(registry.distinctMetaCount(), 1)  // 구별 metadata 수
        val wList = listOf(1, 2, 5, 10, 25, 50).map { (base * it).coerceAtMost(100_000) }.distinct()
        return ResponseEntity.ok(mapOf("k" to k, "base" to base, "wList" to wList))
    }

    @GetMapping("/run")
    fun run(
        @RequestParam(defaultValue = "200,500,1000,2000,4000,8000") w: String,
        @RequestParam(defaultValue = "21") reps: Int,
        @RequestParam(defaultValue = "5") warmup: Int,
        @RequestParam(defaultValue = "10") k: Int,
    ): ResponseEntity<ExperimentRunner.Report> {
        val wList = w.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..1_000_000 }
        val report = runner.run(wList, reps.coerceIn(1, 201), warmup.coerceIn(0, 50), k.coerceIn(1, 200))
        return ResponseEntity.ok(report)
    }
}
