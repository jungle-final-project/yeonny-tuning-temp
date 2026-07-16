-- FPS 단조성 수선: 발로란트/사이버펑크2077/오버워치2 전면 재시드 + 로스트아크 5090 구멍 2셀 + 배그 중복 셀 정리.
-- 배경: 기존 표는 게임별로 소스·프리셋·테스트베드가 혼재되어(예: 발로란트 5060 501 vs 5060Ti 300)
--   같은 해상도에서 상위 GPU가 하위 GPU보다 낮게 표시되는 역전이 있었다. 제품 본질(사용자 확정):
--   "좋은 장비를 쓰면 더 좋아지는 걸 볼 수 있어야 한다" — 정확 FPS 보장이 아니라 참고범위(guaranteePolicy)지만,
--   상위 GPU가 하위 GPU보다 낮게 표시되는 일은 절대 없어야 한다.
-- 조치: 게임당 단일 잣대(같은 소스·같은 프리셋·같은 테스트베드 CPU)로 6 GPU 클래스 × 3해상도를 웹 재검증(2026-07-16)해
--   교체하고, 마이그레이션 맨 끝의 DO 블록이 살아있는 전 행에 대해 단조성 불변식을 강제한다(위반 시 배포 실패).
-- 정책: 정확 FPS 보장 아님(NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE). gpuClass 매칭 + 대표 part_id(V41/V111/V112 관례).

-- 1) 소프트 삭제: 재시드 대상 3게임 전 행
UPDATE game_fps_benchmarks
SET deleted_at = now(),
    updated_at = now()
WHERE deleted_at IS NULL
  AND game_key IN ('valorant', 'cyberpunk-2077', 'overwatch-2');

-- 1-2) 소프트 삭제: 배그 FHD RTX_5060_TI 중복 셀 중 HowManyFPS(ULTRA 213) 행 — 같은 셀에 PC-Builds MEDIUM 209와 공존해
--      셀 중복 금지 불변식을 깨던 행. PC-Builds 행이 배그 나머지 사다리와 같은 잣대라 그쪽을 남긴다.
UPDATE game_fps_benchmarks
SET deleted_at = now(),
    updated_at = now()
WHERE deleted_at IS NULL
  AND game_key = 'pubg'
  AND resolution = 'FHD'
  AND metadata->>'gpuClass' = 'RTX_5060_TI'
  AND source_name LIKE 'HowManyFPS%';

-- 2) 재시드 INSERT: 게임당 단일 잣대 웹검증 데이터셋(발로란트 18 + 사이버펑크 18 + 오버워치 18 + 로스트아크 2 = 56행).
--    gpu_part_id 는 기존 벤치 표가 쓰는 클래스 대표 부품 public_id 로 해석(V41 방식 서브쿼리 JOIN):
--      RTX_5060=조텍 5060 Twin Edge, RTX_5060_TI=기가바이트 5060 Ti WINDFORCE, RTX_5070=MSI 5070 게이밍 트리오,
--      RTX_5070_TI=기가바이트 5070 Ti WINDFORCE, RTX_5080=MSI 5080 쉐도우, RTX_5090=조텍 5090 SOLID.
WITH fps_seed AS (
  SELECT *
  FROM (VALUES
    -- Valorant: 18셀 전부 HowManyFPS 계산기(howmanyfps.com/games/valorant) 직접 판독.
    -- 조건 고정: 프리셋 High(무료 티어 유일 프리셋), 맵 Lotus, CPU Ryzen 7 9800X3D, RAM 32GB, 오프셋 0%. 5060 Ti는 기본 SKU(8GB).
    ('00000000-0000-4000-8000-000000124001'::uuid, 'Valorant', 'valorant', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', 'FHD', '1920 x 1080', 'HIGH', 716.00, 519.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5060+9800X3D, 1920x1080 High Lotus). dropreference 1080p 사다리(5060 546, i9-14900)와 동일 자릿수'),
    ('00000000-0000-4000-8000-000000124002'::uuid, 'Valorant', 'valorant', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', 'QHD', '2560 x 1440', 'HIGH', 663.00, 495.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5060+9800X3D, 2560x1440 High Lotus)'),
    ('00000000-0000-4000-8000-000000124003'::uuid, 'Valorant', 'valorant', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', '4K', '3840 x 2160', 'HIGH', 533.00, 399.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'MEDIUM', 'HowManyFPS 계산기 직접 판독(RTX 5060+9800X3D, 3840x2160 High Lotus). 4K 축은 실측 코퍼스가 얇은 모델 추정 성격이라 MEDIUM'),
    ('00000000-0000-4000-8000-000000124004'::uuid, 'Valorant', 'valorant', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', 'FHD', '1920 x 1080', 'HIGH', 735.00, 533.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5060 Ti 기본 SKU+9800X3D, 1920x1080 High Lotus)'),
    ('00000000-0000-4000-8000-000000124005'::uuid, 'Valorant', 'valorant', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', 'QHD', '2560 x 1440', 'HIGH', 682.00, 509.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5060 Ti+9800X3D, 2560x1440 High Lotus)'),
    ('00000000-0000-4000-8000-000000124006'::uuid, 'Valorant', 'valorant', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', '4K', '3840 x 2160', 'HIGH', 552.00, 414.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'MEDIUM', 'HowManyFPS 계산기 직접 판독(RTX 5060 Ti+9800X3D, 3840x2160 High Lotus). 4K 축 모델 추정 성격이라 MEDIUM'),
    ('00000000-0000-4000-8000-000000124007'::uuid, 'Valorant', 'valorant', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', 'FHD', '1920 x 1080', 'HIGH', 796.00, 577.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5070+9800X3D, 1920x1080 High Lotus)'),
    ('00000000-0000-4000-8000-000000124008'::uuid, 'Valorant', 'valorant', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', 'QHD', '2560 x 1440', 'HIGH', 743.00, 553.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5070+9800X3D, 2560x1440 High Lotus)'),
    ('00000000-0000-4000-8000-000000124009'::uuid, 'Valorant', 'valorant', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', '4K', '3840 x 2160', 'HIGH', 612.00, 459.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'MEDIUM', 'HowManyFPS 계산기 직접 판독(RTX 5070+9800X3D, 3840x2160 High Lotus). 4K 축 모델 추정 성격이라 MEDIUM'),
    ('00000000-0000-4000-8000-000000124010'::uuid, 'Valorant', 'valorant', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', 'FHD', '1920 x 1080', 'HIGH', 835.00, 608.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5070 Ti+9800X3D, 1920x1080 High Lotus)'),
    ('00000000-0000-4000-8000-000000124011'::uuid, 'Valorant', 'valorant', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', 'QHD', '2560 x 1440', 'HIGH', 782.00, 584.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5070 Ti+9800X3D, 2560x1440 High Lotus). HowManyFPS 커뮤니티 실측(5070 Ti+5800X3D 1440p 421avg/391low)보다 상위 CPU 반영'),
    ('00000000-0000-4000-8000-000000124012'::uuid, 'Valorant', 'valorant', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', '4K', '3840 x 2160', 'HIGH', 651.00, 488.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'MEDIUM', 'HowManyFPS 계산기 직접 판독(RTX 5070 Ti+9800X3D, 3840x2160 High Lotus). 4K 축 모델 추정 성격이라 MEDIUM'),
    ('00000000-0000-4000-8000-000000124013'::uuid, 'Valorant', 'valorant', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', 'FHD', '1920 x 1080', 'HIGH', 871.00, 638.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5080+9800X3D, 1920x1080 High Lotus)'),
    ('00000000-0000-4000-8000-000000124014'::uuid, 'Valorant', 'valorant', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', 'QHD', '2560 x 1440', 'HIGH', 818.00, 614.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5080+9800X3D, 2560x1440 High Lotus)'),
    ('00000000-0000-4000-8000-000000124015'::uuid, 'Valorant', 'valorant', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', '4K', '3840 x 2160', 'HIGH', 688.00, 516.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'MEDIUM', 'HowManyFPS 계산기 직접 판독(RTX 5080+9800X3D, 3840x2160 High Lotus). 4K 축 모델 추정 성격이라 MEDIUM'),
    ('00000000-0000-4000-8000-000000124016'::uuid, 'Valorant', 'valorant', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'FHD', '1920 x 1080', 'HIGH', 962.00, 718.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5090+9800X3D, 1920x1080 High Lotus). 기존 표 앵커 1017(HowManyFPS HIGH)과 동일 소스·동일 자릿수'),
    ('00000000-0000-4000-8000-000000124017'::uuid, 'Valorant', 'valorant', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'QHD', '2560 x 1440', 'HIGH', 909.00, 681.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'HIGH', 'HowManyFPS 계산기 직접 판독(RTX 5090+9800X3D, 2560x1440 High Lotus). Tom''s Hardware 5090 1440p ~800FPS 보도와 부합'),
    ('00000000-0000-4000-8000-000000124018'::uuid, 'Valorant', 'valorant', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', '4K', '3840 x 2160', 'HIGH', 778.00, 584.00, 'HowManyFPS', 'https://howmanyfps.com/games/valorant', 'MEDIUM', 'HowManyFPS 계산기 직접 판독(RTX 5090+9800X3D, 3840x2160 High Lotus). 4K 축 모델 추정 성격이라 MEDIUM'),

    -- Cyberpunk 2077: 18셀 전부 pc-builds.com FPS Calculator(Ryzen 7 9800X3D 테스트베드, 레이트레이싱 없는 ULTRA).
    -- avg는 각 GPU 결과 페이지의 FPS distribution graph Ultra 직접값, 1% low는 동일 페이지 Medium표 min/avg 비율(0.84~0.86) 단일 규칙 적용.
    ('00000000-0000-4000-8000-000000124019'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', 'FHD', '1920 x 1080', 'ULTRA', 58.00, 49.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1sm02g/ryzen-7-9800x3d/geforce-rtx-5060/cyberpunk-2077/', 'MEDIUM', '소스 페이지 1920×1080 Ultra 값 58 직접 사용(9800X3D 테스트베드). 1% low = Ultra avg × Medium표 min/avg 비율(70/83=0.843) = 49'),
    ('00000000-0000-4000-8000-000000124020'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', 'QHD', '2560 x 1440', 'ULTRA', 43.00, 36.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1sm02g/ryzen-7-9800x3d/geforce-rtx-5060/cyberpunk-2077/', 'MEDIUM', '소스 페이지 2560×1440 Ultra 값 43 직접 사용. 1% low = 43 × (52/62=0.839) = 36'),
    ('00000000-0000-4000-8000-000000124021'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', '4K', '3840 x 2160', 'ULTRA', 29.00, 25.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1sm02g/ryzen-7-9800x3d/geforce-rtx-5060/cyberpunk-2077/', 'MEDIUM', '소스 페이지 3840×2160 Ultra 값 29 직접 사용. 1% low = 29 × (35/41=0.854) = 25'),
    ('00000000-0000-4000-8000-000000124022'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', 'FHD', '1920 x 1080', 'ULTRA', 63.00, 54.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1Bm02g/ryzen-7-9800x3d/geforce-rtx-5060-ti/cyberpunk-2077/', 'MEDIUM', '소스 페이지 1920×1080 Ultra 값 63 직접 사용. 1% low = 63 × (76/89=0.854) = 54. GN 구 실측(93)보다 보수적이나 단일 잣대 유지'),
    ('00000000-0000-4000-8000-000000124023'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', 'QHD', '2560 x 1440', 'ULTRA', 47.00, 40.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1Bm02g/ryzen-7-9800x3d/geforce-rtx-5060-ti/cyberpunk-2077/', 'MEDIUM', '소스 페이지 2560×1440 Ultra 값 47 직접 사용. 1% low = 47 × (57/67=0.851) = 40'),
    ('00000000-0000-4000-8000-000000124024'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', '4K', '3840 x 2160', 'ULTRA', 31.00, 26.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1Bm02g/ryzen-7-9800x3d/geforce-rtx-5060-ti/cyberpunk-2077/', 'MEDIUM', '소스 페이지 3840×2160 Ultra 값 31 직접 사용(GN 구 실측 4K 31과 일치). 1% low = 31 × (37/44=0.841) = 26'),
    ('00000000-0000-4000-8000-000000124025'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', 'FHD', '1920 x 1080', 'ULTRA', 80.00, 68.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1xO02g/ryzen-7-9800x3d/geforce-rtx-5070/cyberpunk-2077/', 'MEDIUM', '소스 페이지 1920×1080 Ultra 값 80 직접 사용. 1% low = 80 × (97/114=0.851) = 68'),
    ('00000000-0000-4000-8000-000000124026'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', 'QHD', '2560 x 1440', 'ULTRA', 60.00, 51.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1xO02g/ryzen-7-9800x3d/geforce-rtx-5070/cyberpunk-2077/', 'MEDIUM', '소스 페이지 2560×1440 Ultra 값 60 직접 사용. 1% low = 60 × (72/85=0.847) = 51'),
    ('00000000-0000-4000-8000-000000124027'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', '4K', '3840 x 2160', 'ULTRA', 39.00, 33.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1xO02g/ryzen-7-9800x3d/geforce-rtx-5070/cyberpunk-2077/', 'MEDIUM', '소스 페이지 3840×2160 Ultra 값 39 직접 사용. 1% low = 39 × (48/56=0.857) = 33'),
    ('00000000-0000-4000-8000-000000124028'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', 'FHD', '1920 x 1080', 'ULTRA', 90.00, 77.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1wO02g/ryzen-7-9800x3d/geforce-rtx-5070-ti/cyberpunk-2077/', 'MEDIUM', '소스 페이지 1920×1080 Ultra 값 90 직접 사용(Medium은 128 CPU캡이나 Ultra는 캡 없음). 1% low = 90 × (109/128=0.852) = 77'),
    ('00000000-0000-4000-8000-000000124029'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', 'QHD', '2560 x 1440', 'ULTRA', 67.00, 57.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1wO02g/ryzen-7-9800x3d/geforce-rtx-5070-ti/cyberpunk-2077/', 'MEDIUM', '소스 페이지 2560×1440 Ultra 값 67 직접 사용. 1% low = 67 × (82/96=0.854) = 57'),
    ('00000000-0000-4000-8000-000000124030'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', '4K', '3840 x 2160', 'ULTRA', 45.00, 39.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1wO02g/ryzen-7-9800x3d/geforce-rtx-5070-ti/cyberpunk-2077/', 'MEDIUM', '소스 페이지 3840×2160 Ultra 값 45 직접 사용. 1% low = 45 × (54/63=0.857) = 39'),
    ('00000000-0000-4000-8000-000000124031'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', 'FHD', '1920 x 1080', 'ULTRA', 99.00, 84.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1uh02g/ryzen-7-9800x3d/geforce-rtx-5080/cyberpunk-2077/', 'MEDIUM', '소스 페이지 1920×1080 Ultra 값 99 직접 사용(Medium 128 CPU캡, Ultra는 캡 없음). 1% low = 99 × (109/128=0.852) = 84'),
    ('00000000-0000-4000-8000-000000124032'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', 'QHD', '2560 x 1440', 'ULTRA', 74.00, 63.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1uh02g/ryzen-7-9800x3d/geforce-rtx-5080/cyberpunk-2077/', 'MEDIUM', '소스 페이지 2560×1440 Ultra 값 74 직접 사용. 1% low = 74 × (90/106=0.849) = 63'),
    ('00000000-0000-4000-8000-000000124033'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', '4K', '3840 x 2160', 'ULTRA', 49.00, 41.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1uh02g/ryzen-7-9800x3d/geforce-rtx-5080/cyberpunk-2077/', 'MEDIUM', '소스 페이지 3840×2160 Ultra 값 49 직접 사용. 1% low = 49 × (59/70=0.843) = 41'),
    ('00000000-0000-4000-8000-000000124034'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'FHD', '1920 x 1080', 'ULTRA', 109.00, 93.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1ul02g/ryzen-7-9800x3d/geforce-rtx-5090/cyberpunk-2077/', 'MEDIUM', '소스 페이지 1920×1080 Ultra 값 109 직접 사용(Medium 128 CPU캡, Ultra는 캡 없음). 1% low = 109 × (109/128=0.852) = 93'),
    ('00000000-0000-4000-8000-000000124035'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'QHD', '2560 x 1440', 'ULTRA', 81.00, 69.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1ul02g/ryzen-7-9800x3d/geforce-rtx-5090/cyberpunk-2077/', 'MEDIUM', '소스 페이지 2560×1440 Ultra 값 81 직접 사용. 1% low = 81 × (98/115=0.852) = 69'),
    ('00000000-0000-4000-8000-000000124036'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', '4K', '3840 x 2160', 'ULTRA', 53.00, 45.00, 'pc-builds.com FPS Calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1ul02g/ryzen-7-9800x3d/geforce-rtx-5090/cyberpunk-2077/', 'MEDIUM', '소스 페이지 3840×2160 Ultra 값 53 직접 사용. 1% low = 53 × (65/76=0.855) = 45'),

    -- Overwatch 2: 18셀 전부 CheckFPS(checkfps.io) ULTRA 계열 단일 잣대(기존 표 5060 211/5060Ti 266/5070Ti 414 @FHD와 동일 계열).
    -- 5090 3셀만 예외: CheckFPS 5090 페이지가 5080과 동일값(428/377/291) 중복 게재라 같은 소스 5080 값 기반 보수 스케일링(LOW).
    -- 1% low는 CheckFPS 미제공이라 18셀 전부 avg×0.60 고정 비율(DropReference OW2 실측 0.52~0.58 정합) — 단조성 자동 보존.
    ('00000000-0000-4000-8000-000000124037'::uuid, 'Overwatch 2', 'overwatch-2', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', 'FHD', '1920 x 1080', 'ULTRA', 211.00, 127.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5060', 'MEDIUM', 'CheckFPS 1080p Ultra 211fps 직접값(기존 표와 일치). 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124038'::uuid, 'Overwatch 2', 'overwatch-2', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', 'QHD', '2560 x 1440', 'ULTRA', 148.00, 89.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5060', 'MEDIUM', 'CheckFPS 1440p Ultra 148fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124039'::uuid, 'Overwatch 2', 'overwatch-2', '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 'RTX_5060', 'GeForce RTX 5060', '4K', '3840 x 2160', 'ULTRA', 65.00, 39.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5060', 'MEDIUM', 'CheckFPS 4K Ultra 65fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124040'::uuid, 'Overwatch 2', 'overwatch-2', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', 'FHD', '1920 x 1080', 'ULTRA', 266.00, 160.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5060-ti', 'MEDIUM', 'CheckFPS 1080p Ultra 266fps 직접값(기존 표와 일치). 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124041'::uuid, 'Overwatch 2', 'overwatch-2', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', 'QHD', '2560 x 1440', 'ULTRA', 203.00, 122.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5060-ti', 'MEDIUM', 'CheckFPS 1440p Ultra 203fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124042'::uuid, 'Overwatch 2', 'overwatch-2', 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 'RTX_5060_TI', 'GeForce RTX 5060 Ti', '4K', '3840 x 2160', 'ULTRA', 96.00, 58.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5060-ti', 'MEDIUM', 'CheckFPS 4K Ultra 96fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124043'::uuid, 'Overwatch 2', 'overwatch-2', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', 'FHD', '1920 x 1080', 'ULTRA', 361.00, 217.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5070', 'MEDIUM', 'CheckFPS 1080p Ultra 361fps 직접값(기존 구멍 셀). 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124044'::uuid, 'Overwatch 2', 'overwatch-2', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', 'QHD', '2560 x 1440', 'ULTRA', 295.00, 177.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5070', 'MEDIUM', 'CheckFPS 1440p Ultra 295fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124045'::uuid, 'Overwatch 2', 'overwatch-2', 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 'RTX_5070', 'GeForce RTX 5070', '4K', '3840 x 2160', 'ULTRA', 171.00, 103.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5070', 'MEDIUM', 'CheckFPS 4K Ultra 171fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124046'::uuid, 'Overwatch 2', 'overwatch-2', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', 'FHD', '1920 x 1080', 'ULTRA', 414.00, 248.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5070-ti', 'MEDIUM', 'CheckFPS 1080p Ultra 414fps 직접값(기존 표와 일치). 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124047'::uuid, 'Overwatch 2', 'overwatch-2', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', 'QHD', '2560 x 1440', 'ULTRA', 349.00, 209.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5070-ti', 'MEDIUM', 'CheckFPS 1440p Ultra 349fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124048'::uuid, 'Overwatch 2', 'overwatch-2', '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 'RTX_5070_TI', 'GeForce RTX 5070 Ti', '4K', '3840 x 2160', 'ULTRA', 223.00, 134.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5070-ti', 'MEDIUM', 'CheckFPS 4K Ultra 223fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124049'::uuid, 'Overwatch 2', 'overwatch-2', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', 'FHD', '1920 x 1080', 'ULTRA', 428.00, 257.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5080', 'MEDIUM', 'CheckFPS 1080p Ultra 428fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124050'::uuid, 'Overwatch 2', 'overwatch-2', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', 'QHD', '2560 x 1440', 'ULTRA', 377.00, 226.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5080', 'MEDIUM', 'CheckFPS 1440p Ultra 377fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124051'::uuid, 'Overwatch 2', 'overwatch-2', '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 'RTX_5080', 'GeForce RTX 5080', '4K', '3840 x 2160', 'ULTRA', 291.00, 175.00, 'CheckFPS', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5080', 'MEDIUM', 'CheckFPS 4K Ultra 291fps 직접값. 1% low는 avg×0.60 도출'),
    ('00000000-0000-4000-8000-000000124052'::uuid, 'Overwatch 2', 'overwatch-2', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'FHD', '1920 x 1080', 'ULTRA', 449.00, 269.00, 'CheckFPS (5080 기반 스케일링)', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5090', 'LOW', 'CheckFPS 5090 페이지가 5080과 동일값(428) 중복 게재 → 같은 소스 5080 FHD 428×1.05=449 도출. 1080p Ultra는 CPU 병목 구간이라 +5%만 반영(GN 5090vs5080 스케일링은 해상도가 낮을수록 축소). 1% low는 avg×0.60'),
    ('00000000-0000-4000-8000-000000124053'::uuid, 'Overwatch 2', 'overwatch-2', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'QHD', '2560 x 1440', 'ULTRA', 424.00, 254.00, 'CheckFPS (5080 기반 스케일링)', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5090', 'LOW', 'CheckFPS 5090 페이지 중복값 → 5080 QHD 377×1.125=424 도출(경량 타이틀 부분 CPU 병목 감안, GN 1440p +46%의 보수 하향·DropReference OW2 +26% 정합). 1% low는 avg×0.60'),
    ('00000000-0000-4000-8000-000000124054'::uuid, 'Overwatch 2', 'overwatch-2', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', '4K', '3840 x 2160', 'ULTRA', 375.00, 225.00, 'CheckFPS (5080 기반 스케일링)', 'https://checkfps.io/en/fps-calculator/overwatch-2/rtx-5090', 'LOW', 'CheckFPS 5090 페이지 중복값 → 5080 4K 291×1.29=375 도출(GN/TPU 4K +36~60% 대비 보수, DropReference OW2 실측 +26%와 정합). 1% low는 avg×0.60'),

    -- Lost Ark: RTX_5090 QHD/4K 구멍 2셀만 보강. 기존 lost-ark 사다리와 동일 잣대(PC-Builds FPS calculator, Medium),
    -- 계산기 직접 조회(2026-07-16): FHD 283 / QHD 191(min 163) / 4K 110(min 94). 기존 5090 FHD 314(HowManyFPS HIGH)는 유지.
    ('00000000-0000-4000-8000-000000124055'::uuid, 'Lost Ark', 'lost-ark', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', 'QHD', '2560 x 1440', 'MEDIUM', 191.00, 163.00, 'PC-Builds FPS Calculator (Ryzen 7 9800X3D + RTX 5090, Lost Ark)', 'https://pc-builds.com/fps-calculator/result/1Ek1ul0ax/ryzen-7-9800x3d/geforce-rtx-5090/lost-ark/2560x1440/', 'HIGH', 'PC-Builds 계산기 직접 조회 실측값(QHD Medium avg 191, min 163). 기존 5080 QHD 175보다 높아 사다리 순 유지.'),
    ('00000000-0000-4000-8000-000000124056'::uuid, 'Lost Ark', 'lost-ark', '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 'RTX_5090', 'GeForce RTX 5090', '4K', '3840 x 2160', 'MEDIUM', 110.00, 94.00, 'PC-Builds FPS Calculator (Ryzen 7 9800X3D + RTX 5090, Lost Ark)', 'https://pc-builds.com/fps-calculator/result/1Ek1ul0ax/ryzen-7-9800x3d/geforce-rtx-5090/lost-ark/2560x1440/', 'HIGH', 'PC-Builds 계산기 직접 조회 실측값(4K Medium avg 110, min 94). 기존 5080 4K 101보다 높아 사다리 순 유지.')
  ) AS seed(public_id, game_title, game_key, gpu_public_id, gpu_class, source_gpu_name, resolution, resolution_text, graphics_preset, avg_fps, one_percent_low_fps, source_name, source_url, confidence, basis)
)
INSERT INTO game_fps_benchmarks (
  public_id,
  game_title,
  game_key,
  cpu_part_id,
  gpu_part_id,
  resolution,
  graphics_preset,
  avg_fps,
  one_percent_low_fps,
  source_name,
  source_url,
  source_checked_at,
  confidence,
  metadata,
  created_at,
  updated_at
)
SELECT seed.public_id,
       seed.game_title,
       seed.game_key,
       NULL,
       gpu.id,
       seed.resolution,
       seed.graphics_preset,
       seed.avg_fps,
       seed.one_percent_low_fps,
       seed.source_name,
       seed.source_url,
       DATE '2026-07-16',
       seed.confidence,
       jsonb_build_object(
         'aliases', CASE seed.game_key
           WHEN 'valorant' THEN jsonb_build_array('발로', '발로란트', 'valorant')
           WHEN 'overwatch-2' THEN jsonb_build_array('오버워치', '오버워치2', 'overwatch', 'overwatch 2')
           WHEN 'cyberpunk-2077' THEN jsonb_build_array('사이버펑크', '사펑', 'cyberpunk', 'cyberpunk 2077')
           WHEN 'lost-ark' THEN jsonb_build_array('로아', '로스트아크', 'lost ark')
           ELSE jsonb_build_array(seed.game_key)
         END,
         'cpuClass', 'RYZEN_7_9800X3D',
         'gpuClass', seed.gpu_class,
         'basis', seed.basis,
         'guaranteePolicy', 'NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE',
         'evidenceExactness', 'FPS_CALCULATOR_EXACT_PARTS',
         'hardwareScope', 'FPS_CALCULATOR_EXACT_PARTS',
         'sourceMetricType', 'FPS_CALCULATOR_EXACT_PARTS',
         'sourceCpuName', 'Ryzen 7 9800X3D',
         'sourceGpuName', seed.source_gpu_name,
         'sourcePresetText', seed.graphics_preset,
         'sourceResolutionText', seed.resolution_text,
         'sourceCapturedText', concat(seed.resolution, ' ', seed.graphics_preset, ', avg ', seed.avg_fps, ', 1% low ', seed.one_percent_low_fps),
         'sourceAccessMethod', 'MANUAL_PAGE_READ',
         'osVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'driverVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'gameVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'testScene', 'UNKNOWN_PUBLIC_SOURCE',
         'upscaling', 'UNKNOWN_PUBLIC_SOURCE',
         'frameGeneration', 'UNKNOWN_PUBLIC_SOURCE',
         'rayTracing', CASE WHEN seed.game_key = 'cyberpunk-2077' THEN 'OFF' ELSE 'NOT_SPECIFIED' END,
         'qualityGaps', CASE seed.game_key
           WHEN 'overwatch-2' THEN jsonb_build_array('one_percent_low_derived_fixed_ratio_0_60')
           WHEN 'cyberpunk-2077' THEN jsonb_build_array('one_percent_low_derived_from_source_min_avg_ratio')
           WHEN 'lost-ark' THEN jsonb_build_array('one_percent_low_is_source_min_fps')
           ELSE jsonb_build_array()
         END,
         'notes', 'Public FPS reference for recommendation evidence. Do not present as guaranteed FPS.',
         'metadataVersion', 1
       ),
       now(),
       now()
FROM fps_seed seed
JOIN parts gpu ON gpu.public_id = seed.gpu_public_id
ON CONFLICT DO NOTHING;

-- 2-2) 삽입 수 가드: 대표 부품 public_id 미스매치(JOIN 누락)나 unique 충돌 시 조용히 빠지는 것을 배포 실패로 승격.
DO $$
DECLARE
  reseeded_count integer;
BEGIN
  SELECT count(*)
  INTO reseeded_count
  FROM game_fps_benchmarks
  WHERE deleted_at IS NULL
    AND source_checked_at = DATE '2026-07-16'
    AND game_key IN ('valorant', 'cyberpunk-2077', 'overwatch-2', 'lost-ark');
  IF reseeded_count <> 56 THEN
    RAISE EXCEPTION 'V124 재시드 행 수 불일치: 기대 56, 실제 % — 대표 부품 public_id 매칭 또는 unique 인덱스 충돌 확인 필요', reseeded_count;
  END IF;
END $$;

-- 3) 단조성 검증(재발 방지 장치): deleted_at IS NULL 전 행 대상.
--    (a) 같은 game_key×resolution 에서 GPU 사다리(RTX_5060<RTX_5060_TI<RTX_5070<RTX_5070_TI<RTX_5080<RTX_5090) 순
--        avg_fps 엄격 증가 — 행이 있는 클래스끼리 인접 비교. 상위 GPU가 하위 GPU보다 낮게 표시되는 일은 절대 없어야 한다.
--    (b) 같은 game_key×gpuClass 에서 FHD > QHD > 4K 엄격 감소.
--    (c) 같은 game_key×resolution×gpuClass 중복 행 금지.
--    이후 아무 시드가 이 순서를 어겨도 이 블록이 배포를 실패시킨다.
DO $$
DECLARE
  gpu_ladder CONSTANT text[] := ARRAY['RTX_5060', 'RTX_5060_TI', 'RTX_5070', 'RTX_5070_TI', 'RTX_5080', 'RTX_5090'];
  res_ladder CONSTANT text[] := ARRAY['FHD', 'QHD', '4K'];
  violations text;
BEGIN
  -- (c) 셀 중복 금지: 중복이 있으면 (a)/(b)의 인접 비교 자체가 무의미하므로 가장 먼저 검사한다.
  SELECT string_agg(format('game=%s res=%s gpuClass=%s 행 %s개(1개여야 함)', game_key, resolution, gpu_class, cnt), ' | ')
  INTO violations
  FROM (
    SELECT game_key, resolution, metadata->>'gpuClass' AS gpu_class, count(*) AS cnt
    FROM game_fps_benchmarks
    WHERE deleted_at IS NULL
      AND metadata->>'gpuClass' IS NOT NULL
    GROUP BY 1, 2, 3
    HAVING count(*) > 1
  ) dup;
  IF violations IS NOT NULL THEN
    RAISE EXCEPTION 'FPS 단조성 검증 실패(중복 셀): 같은 game_key×resolution×gpuClass 에 행이 2개 이상 — %', violations;
  END IF;

  -- (a) GPU 사다리 엄격 증가 (같은 game_key×resolution, 존재하는 클래스끼리 인접 비교)
  SELECT string_agg(
           format('game=%s res=%s: 상위 %s(avg %s)가 하위 %s(avg %s)보다 낮거나 같음', game_key, resolution, gpu_class, avg_fps, prev_class, prev_fps),
           ' | ')
  INTO violations
  FROM (
    SELECT game_key,
           resolution,
           metadata->>'gpuClass' AS gpu_class,
           avg_fps,
           lag(metadata->>'gpuClass') OVER w AS prev_class,
           lag(avg_fps) OVER w AS prev_fps
    FROM game_fps_benchmarks
    WHERE deleted_at IS NULL
      AND metadata->>'gpuClass' = ANY (gpu_ladder)
    WINDOW w AS (PARTITION BY game_key, resolution ORDER BY array_position(gpu_ladder, metadata->>'gpuClass'))
  ) ladder
  WHERE prev_fps IS NOT NULL
    AND avg_fps <= prev_fps;
  IF violations IS NOT NULL THEN
    RAISE EXCEPTION 'FPS 단조성 검증 실패(GPU 사다리 역전): %', violations;
  END IF;

  -- (b) 해상도 엄격 감소 (같은 game_key×gpuClass 에서 FHD > QHD > 4K)
  SELECT string_agg(
           format('game=%s gpuClass=%s: %s(avg %s)가 더 낮은 해상도 %s(avg %s)보다 높거나 같음', game_key, gpu_class, resolution, avg_fps, prev_res, prev_fps),
           ' | ')
  INTO violations
  FROM (
    SELECT game_key,
           metadata->>'gpuClass' AS gpu_class,
           resolution,
           avg_fps,
           lag(resolution) OVER w AS prev_res,
           lag(avg_fps) OVER w AS prev_fps
    FROM game_fps_benchmarks
    WHERE deleted_at IS NULL
      AND metadata->>'gpuClass' = ANY (gpu_ladder)
    WINDOW w AS (PARTITION BY game_key, metadata->>'gpuClass' ORDER BY array_position(res_ladder, resolution))
  ) res_steps
  WHERE prev_fps IS NOT NULL
    AND avg_fps >= prev_fps;
  IF violations IS NOT NULL THEN
    RAISE EXCEPTION 'FPS 단조성 검증 실패(해상도 역전): %', violations;
  END IF;
END $$;
