# 세션 핸드오프 — JeNAVI 스트리밍 IndexPoint 연구

> 다음 세션의 나에게. 이 문서 + `Doucments/references.md` 를 먼저 읽을 것.
> 코드/논문 사실은 검증된 것만 기재. 상대날짜 없음.

---

## 0. 한 줄 목표
정적 IndexPoint(논문 1편)를 **스트림-네이티브(윈도우 + 증분추론)** 로 끌어올려, **표현력 있는 추론을 저지연으로** 하는 RSP 시스템을 만든다. **목표 게재처: IEEE Internet of Things Journal (IEEE IoT-J).**

---

## 1. 연구 독창성 & 임팩트 (확정된 포지셔닝)

### 자산
- **1편 (IEEE IoT-J 게재)**: *Observation-Metadata-Centric Digital Twin Ontology…* — **IndexPoint** = 온톨로지-유도 **materialized 2차 인덱스**. 결과를 메타데이터(op/uom/odt)로 그룹핑 → **질의 비용을 관측치 수 m이 아니라 메타데이터 카디널리티 k에 묶음**. 정적 DKG에서 최대 **179.75×**. 단, **정적**에서만 증명.
- **RSP 서베이 (Bonte 외, VLDB Journal 2025)**: 1순위 미해결 = **표현력 추론 × 저지연**. materialization 업데이트 비용 문제. 그리고 *"대부분 RSP가 Apache Jena 메모리 스토어를 쓰는데 복잡한 인덱싱 스키마가 없다"* → **우리의 공백**.

### 헤드라인 주장 (★ 이걸로 글을 씀)
> **집계-의존 규칙 + 온톨로지 entailment 를, 슬라이딩 윈도우 하에서 증분 유지하되, 갱신당 비용을 누적 관측치 m이 아니라 메타데이터 카디널리티 k(=flat in m)에 묶는다.**

- **헤드라인은 "flat in m(M)"**. "집계를 한다"는 단독으로는 신규성 아님(I-DLV-sr이 이미 함).
- 고유 셀 = **A ∧ M ∧ L 동시** (A=집계규칙, M=비용 m에 평평, L=온톨로지 메타데이터 국소화).

### 확정 빈칸표 (정독 근거)
| 시스템 | E 온톨로지증분 | A 집계 | M flat-in-m | L 메타데이터국소화 |
|---|---|---|---|---|
| iMARS 2010 (ESWC) | ✓ | ✗ | ✗ | ✗ |
| RoXi 2023 (ESWC) | ✓ (DRed/iMARS) | ✗ (plain Datalog) | ✗ | ✗ — ★future work로 "스트리밍 인덱싱 자료구조"를 open이라 명시 |
| I-DLV-sr 2021/22 (TPLP/RuleML+RR) | ~ (규칙) | ✓ (count in window) | ✗ (overgrounding 단조증가) | ✗ |
| **우리** | ✓ | ✓ | ✓ | ✓ |

### 직계 선행 = iMARS (반드시 차별화)
- iMARS: 각 트리플에 **expiration**(유효 종료시각) 태깅, 추론 트리플 exp = min(전제들 exp). 윈도우 만료 = **검사로 드롭(재유도 0)**. DRed의 비싼 삭제를 공짜로. **단조(RDFS/RL)만, 전역, 집계 없음.** Jena Generic Rule Engine 구현.
- **우리 = iMARS의 expiration 재사용 + IndexPoint 국소화(k) + 집계.** "iMARS는 윈도우 삭제를 풀었다 → 우리는 거기에 비용을 m으로부터 분리 + 집계를 더한다."

### 해소하는 갈등 (서베이 기준)
T1 표현력×저지연, T2 materialization↔rewriting, T3 비용∝m, T4 삭제(TMS)난이도, T5 범용↔도메인, T6 집계/비단조 증분 — IndexPoint 지역성(k)으로 T3 해소, 나머지 완화.

### 정직한 리스크 (남음)
1. **I-DLV-sr의 overgrounding이 정말 m과 함께 증가**하는지 실험 섹션에서 최종 확정 (M 셀 생사). 텍스트 "monotonically growing"은 강한 신호.
2. discriminating 실험 필수: `avg(temp)>28 ∧ humidity>70 → HeatRisk`(집계+entailment), **m을 2ᵐ 스윕** → baseline 증가 vs 우리 평평. 이 한 장이 논문의 전부.
3. 집계 없는 순수 RDFS만 하면 = "빨라진 RoXi"일 뿐. **반드시 집계+entailment 결합 시나리오.**

---

## 2. 읽은 논문 (전부 `Doucments/`)
- 자산/배경: Observation-Metadata-Centric DT Ontology (1편), RDF stream processing survey (Bonte 2025), OTU.pdf(미정독).
- 직계/토대 (정독): **iMARS**(ESWC2010), **Maintenance of Datalog Materialisations Revisited**(Motik, AIJ2019), **Maintaining Views Incrementally**(Gupta, SIGMOD1993), **Laser**(ISWC2017).
- 경쟁자 (정독): **RoXi**(ESWC2023), **I-DLV-sr**(TPLP2021 + RuleML+RR2022).
- 가볍게: **Streaming MASSIF**(MDPI Sensors2018 — 비중↓), **Ticker**(TPLP2017, 선택).
- 베이스라인/벤치마크(폴더 외): C-SPARQL, CQELS, **RSP4J/Yasper**(올라탈 표준), RDFox-Stream(미입수), **CityBench**(도메인), SRBench, **CSRBench**(정확성 오라클), YABench.
- 상세 = `Doucments/references.md` (제목·정식게재처·요약·우리와의관계·빈칸표).

---

## 3. 프레임워크 / 구현 현황

### 3-계층 모델 (★ 무엇이 연구이고 무엇이 배관인지)
- **① 인입/등록 평면** (배관, 신규성 0): MQTT 인입, FROST REST로 MDS 열거, 레지스트리.
- **② 델타 구성** (배관): thin 이벤트 → 윈도우 트리플.
- **③ 윈도우 + 증분추론 엔진** (★연구 본체): per-IndexPoint 윈도우, 삽입/삭제(TMS)/집계 증분, k-국소화.
- 원칙: ③을 추상 `StreamSource` 뒤에 두고 **FileReplay로 먼저** 검증, MQTT는 나중(배관). ③만이 신규성.

### 두 레인
- **라이브**: FROST REST `$expand`(1회) + FROST MQTT(스트림) → Jenavi in-process. **Kafka 안 씀.**
- **벤치마크**: Python 생성기 → definition.ttl + replay 로그 → FileReplaySource → ③. 결정적.
- **in-mem vs TDB**: 윈도우(W)는 본질적으로 인메모리(경계·최근). TDB는 직교한 콜드 아카이브. **둘 중 택1 강요 안 함.**

### Jenavi (Kotlin/Spring Boot 3.1.2, Kotlin 1.8.22, JDK17, Apache Jena 5.5.0)
- 기존: REST `/api/*`(query/browse/status/init/ingest/upload/rules), TDB2/in-memory 듀얼(`Ontology.readTx/writeTx` 락), `applyRulesAndMaterialize`(Jena GenericRuleReasoner=iMARS와 동일 substrate), WebSocket, Kafka(구).
- 코드리뷰 수정 완료: Kafka 인메모리 레이스(writeTx), browse SPARQL `owl|`→`owl:`, 데드코드 제거.
- **MQTT 인입 (신규, `src/main/kotlin/jenavi/mqtt/`, `…/frost/`)**:
  - `MqttObservationDriver`(Paho v3): 연결·구독·파싱. **@iot.id 없는 메시지(원본 create echo) 폐기** → 중복/시각누락 제거.
  - `FrostClient`: `/MultiDatastreams?$expand=Thing($select=name)` 열거 → per-MDS 토픽 + `MdsThingIndex`(mds→Thing이름).
  - `ObservationEventHandler` = 인입↔엔진 **경계 인터페이스**. 현재 구현 = `LoggingObservationHandler`(로그만). ③ 엔진이 이걸 @Primary로 대체할 자리.
  - `RecentObservationBuffer`(모니터링, 드라이버가 항상 기록 — 핸들러와 독립). `MqttConsumerState`(채택/폐기 카운터).
  - REST: `/mqtt/start|stop|status|recent`. autoDiscover 기본 on.
- 설정(env, `OntologyProperties`): `MQTT_BROKER`(기본 `tcp://aidtlab.com:60500`), `FROST_REST_BASE`(`http://aidtlab.com:60501/FROST-Server/v1.1`), `MQTT_AUTO_DISCOVER`, `USE_TDB` 등.
- 프론트(Vue3+TS, `frontend/`): **"실시간" 탭**(MqttView — start/stop, 채택/폐기, 최근 관측치 표: 수신시각·Thing·MDS·ObsID·time·result, 1.5s 폴링). FastView에 **Thing 섹션** 추가(정의 ABox 미적재라 현재 빈칸). 빌드→`src/main/resources/static/` 탑재→`bootJar`(`build/libs/Jenavi-0.0.2-SNAPSHOT.jar`, 189M, 프론트 내장).
- 실행: `java -Xms64g -Xmx64g -jar …`(머신 RAM 충분). 주의: SPA 딥링크 직접 새로고침 404(기존 한계). bootRun은 build.gradle `-Xms64g`로 일반머신 부적합(이 머신은 OK).

### STA-RDF-Streamer (Python, **별도 repo `C:/Git/STA-RDF-Streamer`**, **커밋 취소됨 = 보류**)
- STA OWL **1.3** 변환기. `build_definition()`(정적) / `build_delta()`(슬림) 분리, **결정적 URI**(멱등), manifest=replay 로그. rdflib, staplugin conda env에서 검증됨.
- **용도 재정의**: 라이브 변환기가 아니라 **벤치마크 합성 데이터 생성기**(2ᵐ 스윕용). 라이브 per-message 변환은 Jenavi(Kotlin)로 흡수.
- 참고 원본: `C:/Git/PaperCode--…/submodules/sta_json_to_rdf.py`, 구버전 `C:/Git/{STA_Plugin, STA-RDF-Publisher, KafkaSTA}`.

### 핵심 데이터 사실
- FROST: REST `http://aidtlab.com:60501/FROST-Server/v1.1/`, MQTT `mqtt://aidtlab.com:60500`(1883은 내부광고값). **MultiDatastream 20개**(CCTV*_OCCUPANCY = result[3]; 환경센서 mds 29/30/31 = result[10] temp/humidity/pressure/…).
- STA OWL 1.3: `https://paper.9bon.org/ontologies/sensorthings/1.3#` — **camelCase**, 상위클래스 `ObservationMetadata`←{ObservedProperty,UnitOfMeasurement,ObservationDataType}, 상위프로퍼티 `pointToMetadata`←{pointTo*}. 주요 술어: `hasIndexPoint, pointToResult, pointToObservedProperty/UnitOfMeasurement/ObservationDataType, hasValue, hasPhenomenonTime`.
- MQTT 메시지: 관측치당 2건 옴 — (a) 원본 create echo(`@iot.id` 없음, 시각 로컬KST 가능) + (b) FROST 알림(`@iot.id`+UTC). **@iot.id로 필터**(완료).
- mds=22(CCTV) phenomenonTime 클럭 스큐 ~4h — 별도 가상센서 이슈, **무시**(워터마크가 흡수).

---

## 4. 현재 위치 & 다음 단계
- ✅ ① 라이브 인입(MQTT+발견+필터+모니터 탭). **적재는 0(의도)** — 핸들러는 로그뿐, 온톨로지 미반영.
- ⬜ **레지스트리**(mds → result[]인덱스 → IndexPoint/메타데이터 URI). `/MultiDatastreams(id)?$expand=ObservedProperties` + 인라인 uom/odt. ②③의 전제.
- ⬜ ② 델타 구성(이벤트 → 윈도우 트리플).
- ⬜ ③ **윈도우 증분추론 엔진** (FileReplay로 먼저, 추상 StreamSource 뒤).

### 코드 들어가기 전 우선 (디리스크)
1. **I-DLV-sr 실험 섹션 정독** → overgrounding/per-update 비용이 m과 함께 증가 최종 확정 (M 셀 = 논문 생사).
2. **discriminating 실험 설계** 확정(집계+entailment, m 스윕, baseline=I-DLV-sr/RoXi/RDFox-Stream, 정확성=CSRBench, 도메인=CityBench, 표준=RSP4J/RSP-QL).
3. 그다음 레지스트리→②→③.

### 미해결/주의
- RDFox-Stream 논문 미입수(윈도우 하 집계 증분 여부 미확인).
- ③의 형식 프래그먼트(어떤 규칙/질의 클래스가 O(k)인지) 명세·건전성 증명 필요.
- 인용 위생: Laser→ISWC2017, Ticker→TPLP2017, Motik→AIJ269(2019), I-DLV-sr→TPLP2021/RuleML+RR2022 (arXiv 아님).

---

## 5. 사용자/협업 메모
- 사용자 = 1편 저자(IEEE IoT-J). 의사결정 신중(만들기 전 재검토 선호). 정직한 진단 선호(응원 금지).
- 합의: 변환기는 분리 유지하되 라이브 per-message는 Jenavi 흡수 / Kafka는 연구경로에서 제외 / 윈도우 엔진 먼저, 배관 나중 / 기여 프레이밍 = **표현력-지연 프론티어**.
