# 삽입 비용 실험 결과 (§11 실험1 트윈-측 조각) — 2026-07-27

**질문**: 온톨로지가 커지면(m↑) Observation 서브그래프 삽입이 느려지는가?
**방법**: `/experiment/insert` — 격리 plain Model에 STA-1.3 Observation 서브그래프를 N개 붙이며
배치별 관측당 삽입시간(perObsUs)을 누적 트리플 수(m)에 대해 측정. JIT 워밍업, source=seed(오프라인).
원자료: `bench_insertion_seed.{json,csv}`(균질), `bench_insertion_hetero.{json,csv}`(이질).

## 결과 요약
| 실험 | 템플릿 | results/obs | m 범위(트리플) | perObsUs 첫→끝 | last/first | 지속 처리량 |
|---|---|---|---|---|---|---|
| A 균질(occupancy×5) | 5 | 3.0 | 0.26M → 5.2M (20×) | 71 → 104 µs | **1.46×** | ~11.8k obs/s |
| B 이질(occ+wht+oaq+solar×23) | 23 | 5.65 | 0.45M → 8.9M (20×) | 122 → 150 µs | **1.23×** | ~7.5k obs/s |

(잡음: A max 141 / B max 213 µs — 단일 실행 GC 지터 포함.)

## 해석 (정직)
- **삽입은 m에 거의 평평.** 데이터 20× 증가에 관측당 비용은 1.2~1.5×만 상승 — 그것도 상당부분 GC/메모리 지터로 보이며, 스캔성(O(m)) 폭증이 아님. **O(Δ) 삽입 설계 확인.**
- 대조: OTU의 SPARQL-갱신은 n 100×에 시간 ~50×(0.08→3.90s). 여기 서브그래프 attach는 그런 폭증 없음.
- **결론: "온톨로지 갱신이 비싸다"는 전제는 실측으로 약함.** 문제를 삽입/쓰기 비용에 세우면 안 됨(§11 피벗 확증).

## 함의 (다음)
- 우리 규모(23센서×1Hz=23 obs/s)는 삽입 처리량 상한(~7.5k~11.8k obs/s)에서 한참 아래 → **삽입 단독으론 예산이 안 생김.**
- 따라서 §11의 실험2(쓰기-읽기 경합: materialize 빈도↑ → 질의 지연↑)와 실험3(무한성장/메모리·질의 악화)로 이동해야 "예산/문제"가 실재하는지 확인 가능. 삽입 곡선은 *반증 자료*로서 논문 related-work·동기부에서 "쓰기비용은 문제가 아니다"를 못박는 데 사용.

## 재현
```
GET /experiment/insert?n=200000&batch=10000&source=seed&templates=5    # 균질
GET /experiment/insert?n=200000&batch=10000&source=seed&templates=23   # 이질
GET /experiment/insert?...&source=frost&templates=5                    # aidtlab MDS 템플릿(읽기전용)
```
