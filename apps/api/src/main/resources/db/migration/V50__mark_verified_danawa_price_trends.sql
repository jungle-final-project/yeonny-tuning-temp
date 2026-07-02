-- Mark manually checked Danawa trend seeds with the product title used for verification.
-- Search-derived trend rows are quarantined in V49; only these title-checked rows
-- should be treated as user-visible Danawa trend evidence.
WITH verified(product_code, product_title) AS (
  VALUES
    ('79558178', '[다나와] NZXT H9 Flow RGB+ V2 (화이트)'),
    ('15279728', '[다나와] DEEPCOOL AK620 (블랙)'),
    ('63064838', '[다나와] CORSAIR iCUE LINK TITAN 360 RX RGB (블랙)'),
    ('77860325', '[다나와] NZXT KRAKEN ELITE V2 360 RGB (화이트)'),
    ('76511312', '[다나와] ASUS PRIME 지포스 RTX 5070 Ti D7 16GB 대원씨티에스'),
    ('74970929', '[다나와] ZOTAC GAMING 지포스 RTX 5080 SOLID OC D7 16GB'),
    ('90955973', '[다나와] MSI 지포스 RTX 5060 쉐도우 2X OC D7 8GB'),
    ('94028963', '[다나와] GIGABYTE 지포스 RTX 5060 WINDFORCE MAX OC D7 8GB 제이씨현'),
    ('122676913', '[다나와] ASUS ROG STRIX X870E-A GAMING WIFI7 NEO STCOM'),
    ('74255387', '[다나와] GIGABYTE B860M AORUS ELITE WIFI6E ICE 피씨디렉트'),
    ('98543660', '[다나와] GIGABYTE X870E AORUS PRO X3D ICE 피씨디렉트'),
    ('74340266', '[다나와] MSI MAG B860M 박격포 WIFI'),
    ('49642352', '[다나와] 마이크로닉스 Classic II 풀체인지 500W 80PLUS브론즈 ATX3.1'),
    ('86079881', '[다나와] CORSAIR HX1200i 80PLUS플래티넘 ATX3.1'),
    ('122693539', '[다나와] 쿨러마스터 Elite Platinum 1000W ATX 3.1 풀모듈러 블랙'),
    ('98410109', '[다나와] FSP HYDRO G PRO 1000W 80PLUS골드 풀모듈러 ATX3.1'),
    ('90576938', '[다나와] CORSAIR RM1000e ETA플래티넘 ATX3.1 화이트'),
    ('18911780', '[다나와] 삼성전자 DDR5-5600 (16GB)'),
    ('20644043', '[다나와] 삼성전자 DDR5-5600 (32GB)')
)
UPDATE price_snapshots ps
SET raw_payload = ps.raw_payload
                  || jsonb_build_object(
                       'matchValidated', true,
                       'productTitle', v.product_title,
                       'verificationMethod', 'manual-title-check-20260701'
                     )
FROM verified v
WHERE ps.source = 'DANAWA_PRICE_TREND'
  AND ps.raw_payload->>'selectorVersion' = 'manual-danawa-pcode-backfill-v1'
  AND ps.raw_payload->>'productCode' = v.product_code;
