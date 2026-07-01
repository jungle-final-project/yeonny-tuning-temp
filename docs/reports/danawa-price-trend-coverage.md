# 다나와 월별 가격 추이 수동 매칭 리포트

작성일: 2026-07-01  
수집 source: `DANAWA_PRICE_TREND`  
수집 방식: 다나와 상품 상세의 공개 `getProductPriceList.ajax.php` 6개월 그래프 포인트 + 현재가 포인트 저장

## 요약

- ACTIVE 내부 자산: 286개
- 수동 감사 통과 자산: 161개
- 수동 감사 제외 자산: 87개
- 확정 pcode 미수집 자산: 38개
- 사용자 화면에 노출되는 검증 추이 포인트: 1,101개
- 중복 `(part_id, source, collected_at)` 그룹: 0개
- 상품별 최대 포인트: 7개
- 상품별 최소 포인트: 2개

`DANAWA_PRICE_TREND`는 `parts.price`를 변경하지 않는다. 월별 포인트는 해당 월 1일 00:00 KST, 현재가 포인트는 현재일 12:00 KST를 `collected_at`으로 저장한다. API 응답에서는 DB 시간대 설정에 따라 UTC 문자열로 보일 수 있다.

2026-07-01 정밀검진에서 일반 검색 결과 첫 `pcode`를 신뢰한 자동 수집분이 노트북, 완제품 PC, 파워뱅크, 인접 GPU/메인보드 모델로 잘못 매칭되는 사례가 확인되었다. `V49__quarantine_unverified_danawa_price_trends.sql`은 최초 자동 검색 seed를 제거하고, `V51__danawa_price_trend_manual_audit_round2.sql`은 수동 감사 통과분만 다시 whitelist 방식으로 저장한다.

전수 검증 원본 표는 `docs/reports/danawa-price-trend-manual-verification.tsv`에 둔다. 이 표는 ACTIVE 내부 자산 286개 전체를 `VERIFIED_KEEP`, `REJECTED_MANUAL_AUDIT`, `MISSING_NO_CONFIDENT_MATCH`로 분류한다.

## 카테고리별 Coverage

| Category | ACTIVE | 수동 감사 통과 | 누락/제외 | 저장 포인트 |
|---|---:|---:|---:|---:|
| CASE | 18 | 9 | 9 | 63 |
| COOLER | 28 | 16 | 12 | 109 |
| CPU | 17 | 11 | 6 | 77 |
| GPU | 58 | 33 | 25 | 231 |
| MOTHERBOARD | 60 | 34 | 26 | 229 |
| PSU | 65 | 37 | 28 | 245 |
| RAM | 20 | 10 | 10 | 70 |
| STORAGE | 20 | 11 | 9 | 77 |

## 수동 감사 기준

가격 추이 그래프는 사용자가 상품 상세에서 직접 보는 데이터이므로 수량보다 정확성을 우선한다. 다음 중 하나라도 어긋나면 사용자 화면에 노출하지 않는다.

- GPU: 세대, Ti 여부, VRAM, 제조사 라인업, 색상/WHITE variant
- CPU: 정확한 모델명, 벌크/트레이/정품/멀티팩 구분, 특수 edition
- 메인보드: 칩셋, 폼팩터, WiFi 세대, ICE/화이트/DARK HERO 같은 variant, 라인업
- PSU: 정격 와트, 효율등급, 모델 패밀리, 색상/WHITE variant
- RAM: DDR5 속도, 용량, 킷 구성, 라인업
- STORAGE: 용량, 모델명, 히트싱크/중고/파생 모델
- CASE/COOLER: 모델 세대, LCD/INF/A-RGB/RGB/색상/라디에이터 규격

## 상태별 의미

| Status | 의미 |
|---|---|
| `VERIFIED_KEEP` | 내부 자산명과 다나와 상세 제목을 수동 감사해 유지한 항목 |
| `REJECTED_MANUAL_AUDIT` | pcode 후보는 있었지만 모델/색상/규격 차이로 제거한 항목 |
| `MISSING_NO_CONFIDENT_MATCH` | 확정할 수 있는 다나와 가격비교 상세 pcode 또는 추이 포인트를 찾지 못한 항목 |

## 포인트가 7개 미만인 수집 자산

일부 다나와 상세 페이지는 6개월 그래프의 월별 포인트가 6개보다 적거나, 현재가만 확인되는 상태다. 이 경우 공개 응답에 있는 포인트만 저장했다.

| Category | Part ID | Name | Points |
|---|---|---|---:|
| COOLER | `00000000-0000-4000-8000-000000011710` | ROG Ryujin III 360 ARGB | 4 |
| MOTHERBOARD | `f786cb93-b28b-4a1f-ba83-f91dd210bb54` | ASRock B860M Rock WiFi 디앤디컴 | 5 |
| MOTHERBOARD | `74b36be0-1910-4525-90b1-8943d17a6284` | ASRock B860M Rock WiFi 디앤디컴 인텔 메인보드 | 5 |
| MOTHERBOARD | `1691009e-d2aa-4d57-9e8f-219c24ad5544` | ASUS ROG STRIX X870E-A GAMING WIFI7 NEO 대원씨티에스 | 2 |
| PSU | `eef983c2-20e1-41d7-95ba-9e497018751d` | 쿨러마스터 ELITE GOLD 1000 ATX 3.1 풀모듈러 | 4 |
| PSU | `33310ce7-48cd-4d5e-865a-a6a869d1985c` | 쿨러마스터 ELITE GOLD 1000 ATX 3.1 풀모듈러 1000W, 블랙 | 4 |
| PSU | `638d308d-7180-4fb8-bb0f-0856c36eb9f5` | 쿨러마스터 Elite Platinum ATX 3.1 풀모듈러 1000W, 블랙 | 2 |
| PSU | `89158fd4-27c2-4b00-8506-108eeb93cf60` | 슈퍼플라워 SF-1000C12DG COMBAT DG ETA골드 ATX3.1 1000W, 블랙 | 4 |

## 검증 SQL

```sql
SELECT category,
       count(*) active_parts,
       count(*) FILTER (WHERE has_trend) trend_parts,
       count(*) FILTER (WHERE NOT has_trend) missing_parts,
       sum(point_count) trend_points
FROM (
  SELECT p.category,
         EXISTS (
           SELECT 1
           FROM price_snapshots ps
           WHERE ps.part_id = p.id
             AND ps.source = 'DANAWA_PRICE_TREND'
         ) has_trend,
         (
           SELECT count(*)
           FROM price_snapshots ps
           WHERE ps.part_id = p.id
             AND ps.source = 'DANAWA_PRICE_TREND'
         ) point_count
  FROM parts p
  WHERE p.status = 'ACTIVE'
    AND p.deleted_at IS NULL
) s
GROUP BY category
ORDER BY category;
```
