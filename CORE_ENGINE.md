# Jenavi 연구 코어 — 윈도우 증분 IndexPoint 유지 엔진

이 문서는 ③ 연구 코어(windowed incremental reasoning engine)의 확정 설계다.
①MQTT 인입 / ②델타 평면은 이미 동작(배관). 이 문서가 다루는 것은 그 위의 엔진.

## 1. 테제 (확정본)
> 온톨로지 도출 IndexPoint 파티션을, **멤버십 엣지의 insert/delete 대수**로 스트림 도중
> **O(Δ+k)** 에 증분 유지하여, 이종 센서 융합이 메타데이터 **비정상성(특히 단위 드리프트)**
> 하에서도 조용히 오염되지 않게 한다.

핵심 통찰: **문헌이 "어렵다"(belief revision·재파티션)고 다루던 비정상 파티션 유지를,
멤버십 엣지의 insert/delete로 표현하여 *이미 쉬운* 윈도우 문제로 강등한다.**

강등을 가능케 하는 3요소 — (1) 온톨로지가 그룹핑을 미리 해둠(런타임 group-by 없음),
(2) 변화가 update가 아니라 **edge insert/delete**(국소, belief-revision 회피),
(3) 집계가 **distributive(가역 ±)**. 윈도우 만료가 전이를 알아서 처리.

**청구 스코프(정직):** 집계/멤버십 층 + distributive 집계(count/sum/avg)만.
스코프 밖(다시 어려워짐): max/min(비가역), full entailment 삭제(비단조 truth maintenance).

## 2. 왜 group-by도, 무한 누적도 없나 (숫자, 현재 fleet 23센서·1Hz)
- result 값 유입 = 130 값/초. **m(누적)** = 하루 1,120만 — **절대 만지지 않음**.
- **k(IndexPoint)** ≈ 25~30, 상수. `temperature/°C`는 wht5+oaq5+solar3 = 13센서가 한 점으로.
- 윈도우 W=60s: 상주량 = 130×60 = 7,800 값(상수). 슬라이드당 Δ = 삽입130+만료130 = 260(상수).
- **슬라이드당 비용 = O(Δ+k) ≈ 290 연산/초, 영원히.** naive(pointToResult 물질화) = O(m) 무한 증가.

group-by 불필요: `(mds_id, result_index) → IndexPoint`가 레지스트리 O(1) 직결(스캔·조인 없음).

## 3. 구성요소
### 3.1 MetadataRegistry — 비정상 파티션의 자료구조
- `edge: (mdsId, resultIndex) → IndexPointKey`  (IndexPointKey = op 개체, 예: `temperature`)
- **런타임 가변**: `insertEdge`, `deleteEdge`, `rerouteEdge(old→new)`. ← 우리 기여의 심장.
- 실험 모드 기본값은 발생기 프로필(occupancy/wht/oaq/solar)과 일치하게 시드.
- 단위 드리프트 = `rerouteEdge((oaq3,3): temperature/°C → temperature/°F)` = delete+insert.

### 3.2 WindowedIndexPoint — IndexPoint별 윈도우 집계
- 시간순 deque `(t, value)` + running `sum, count`.
- `insert(t, v)`: push, `sum+=v; count++`.
- `expire(now-W)`: 앞에서 pop, `sum-=v; count--`.
- `average = sum/count`. (distributive만; max/min 미청구.)

### 3.3 WindowedReasoningEngine
- `Map<IndexPointKey, WindowedIndexPoint>`.
- onEvent(ObservationEvent): result[i]를 numeric 파싱 → registry로 IndexPointKey 조회 → 해당 IP insert.
- 슬라이드 타이머(1s): 전 IP expire + **materialize**(3.4).

### 3.4 온톨로지 반영(materialize) — STA 1.3 준수 (OWL verbatim 기반)
- **IndexPoint = per-MultiDatastream** (OWL: `isIndexPointByMultiDatastream exactly 1`,
  `pointToMetadata exactly 1`). metadata 개체(op/uom)는 MDS 간 공유. **교차센서 융합은
  같은 metadata를 공유하는 per-MDS IndexPoint들의 2차 집계**(전역 IndexPoint 아님).
- 관측 1건 = **Observation 서브그래프**를 도착 시 attach(insert) / 윈도우 만료 시 detach(delete):
  ```
  MultiDatastream -hasObservation-> Observation -hasResult-> Result -hasValue-> "v"(xsd:string)
  Observation -hasPhenomenonTime-> t(xsd:dateTimeStamp), -isObservationOfMultiDatastream-> MDS
  Result -isResultByObservation-> Observation, -hasObservedProperty->op, -hasUnitOfMeasurement->uom
  IndexPoint(MDS,op,uom) -pointToResult-> Result, -pointToMetadata->op, -isIndexPointByMultiDatastream->MDS
  ```
  → 그래프 거주량은 m이 아니라 **W에 한정**(만료 detach). "insert/delete of Observation subgraphs
  = 윈도우"의 *literal 구현*.
- per-MDS IndexPoint마다 집계 `hasWindowedAverage/Count/Sum` + `windowSpec`/`asOf`(시간상대 스냅샷 주석).
- 슬라이드마다 1 writeTx로 일괄(pending attach + 만료 detach + 집계). 비용 O(Δ+k).

### 3.5 WindowedReasoningHandler (@Primary ObservationEventHandler)
- `enabled` 토글. off면 (기존처럼) 통과/로깅만. on이면 engine.onEvent 호출.
- 모니터링 버퍼 기록은 드라이버가 항상 하므로 무관.

## 4. 프론트/엔드포인트 — "반영" 버튼
- `/mqtt/apply?on=true|false` → 엔진 enable/disable + 슬라이드 타이머 start/stop.
- 프론트: **MQTT 시작 버튼 다음에 "반영(온톨로지 적용)" 토글 버튼**.
  누르면 엔진이 스트림을 윈도우 유지 + IndexPoint 집계를 온톨로지에 반영.
- 모니터에 엔진 상태 + 상위 IndexPoint 집계(key, count, avg, asOf) 노출.

## 5. 증분 로드맵
- **Inc.1 (지금)**: Registry + WindowedIndexPoint + Engine + Handler 토글 + `/mqtt/apply` + 프론트 버튼
  + 인메모리 집계 노출. (동작하는 윈도우 유지 코어.)
- **Inc.2**: 온톨로지(Jena 모델) 집계 트리플 materialize + SPARQL 노출.
- **Inc.3**: 드리프트 주입 채널(rerouteEdge 런타임 API) + 단위-드리프트 안전 데모.
- **Inc.4**: 실험 캠페인 — flat vs O(m) 크로스오버, per-slide 지연, OTU 베이스라인, 합성 스케일업.

## 6. 실험이 통과를 결정 (IoT-J)
아이디어는 급이 되나, 통과는 Inc.4의 실측 그래프에 달림:
경쟁자가 무너지는 구간(고-k·고-churn)에서 우리가 flat임을, **OTU(정적 템플릿)를 베이스라인**으로,
합성 스케일업으로 증명. overclaim 금지("reasoning" 아님 → "incremental view maintenance under
non-stationary partition").
