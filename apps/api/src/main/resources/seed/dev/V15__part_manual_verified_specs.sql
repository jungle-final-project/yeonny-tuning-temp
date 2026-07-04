UPDATE parts
SET status = 'INACTIVE',
    attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'inactiveReason', 'NON_PRODUCT_OR_RENTAL_FILTER'
    )),
    updated_at = now()
WHERE category = 'GPU'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL
  AND (
    name ILIKE '%GPU 없음%'
    OR name ILIKE '%그래픽 카드GPU 없음%'
    OR name ILIKE '%피규어%'
    OR name ILIKE '%장식%'
    OR name ILIKE '%모형%'
    OR name ILIKE '%수집 가능한 모델%'
    OR name ILIKE '%렌탈%'
    OR name ILIKE '%대여%'
  );

WITH verified_specs(category, name_pattern, priority, specs) AS (
  VALUES
    ('CASE', '%MESHIFY 3 XL%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.fractal-design.com/app/uploads/2025/05/Meshify-3-XL_Product_Sheet_EN.pdf',
      'formFactor', 'EATX_ATX_MATX_ITX',
      'maxGpuLengthMm', 512,
      'maxCpuCoolerHeightMm', 182,
      'maxPsuLengthMm', 230,
      'gpuSlotHeightMm', 189,
      'radiatorSupportMm', to_jsonb(ARRAY[120, 140, 240, 280, 360, 420]),
      'depthMm', 575,
      'widthMm', 245,
      'heightMm', 515
    )),
    ('CASE', '%MESHIFY 3%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.fractal-design.com/app/uploads/2025/01/Meshify-3_Product_Sheet_EN.pdf',
      'formFactor', 'ATX_MATX_ITX',
      'maxGpuLengthMm', 349,
      'maxCpuCoolerHeightMm', 173,
      'maxPsuLengthMm', 180,
      'gpuSlotHeightMm', 176,
      'radiatorSupportMm', to_jsonb(ARRAY[120, 240, 280, 360]),
      'depthMm', 433,
      'widthMm', 229,
      'heightMm', 507
    )),
    ('CASE', '%LANCOOL 217%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://lian-li.com/product/lancool-217/',
      'formFactor', 'SSI_EEB_EATX_ATX_MATX_ITX',
      'maxGpuLengthMm', 380,
      'maxCpuCoolerHeightMm', 180,
      'maxPsuLengthMm', 220,
      'radiatorSupportMm', to_jsonb(ARRAY[120, 140, 240, 280, 360]),
      'depthMm', 482,
      'widthMm', 238,
      'heightMm', 503
    )),
    ('CASE', '%H9 FLOW%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://nzxt.com/products/h9-flow',
      'formFactor', 'EATX_ATX_MATX_ITX',
      'maxGpuLengthMm', 459,
      'maxGpuLengthWithFrontRadiatorMm', 410,
      'maxCpuCoolerHeightMm', 165,
      'maxPsuLengthMm', 200,
      'radiatorSupportMm', to_jsonb(ARRAY[120, 140, 240, 280, 360, 420]),
      'depthMm', 481,
      'widthMm', 315,
      'heightMm', 506
    )),
    ('CASE', '%FRAME 4000D%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.corsair.com/us/en/explorer/diy-builder/cases/corsair-frame-4000-series/',
      'formFactor', 'EATX_ATX_MATX_ITX',
      'maxGpuLengthMm', 430,
      'maxGpuLengthWithFrontFansMm', 405,
      'maxCpuCoolerHeightMm', 170,
      'maxPsuLengthMm', 220,
      'radiatorSupportMm', to_jsonb(ARRAY[120, 140, 240, 280, 360]),
      'depthMm', 487,
      'widthMm', 239,
      'heightMm', 486
    )),
    ('CASE', '%EVOLV X2%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://phanteks.com/product/evolv-x2-black/',
      'formFactor', 'EATX_ATX_MATX_ITX',
      'maxGpuLengthMm', 380,
      'maxGpuWidthMm', 170,
      'maxCpuCoolerHeightMm', 170,
      'maxPsuLengthMm', 250,
      'radiatorSupportMm', to_jsonb(ARRAY[120, 240, 360]),
      'depthMm', 454,
      'widthMm', 228,
      'heightMm', 588
    )),
    ('CASE', '%LIGHT BASE 900%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.bequiet.com/en/case/5292',
      'formFactor', 'EATX_ATX_MATX_ITX',
      'maxGpuLengthMm', 495,
      'maxCpuCoolerHeightMm', 190,
      'maxPsuLengthMm', 225,
      'radiatorSupportMm', to_jsonb(ARRAY[120, 140, 240, 280, 360, 420]),
      'depthMm', 532,
      'widthMm', 327,
      'heightMm', 484
    )),
    ('COOLER', '%LIQUID FREEZER III%360%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.arctic.de/en/Liquid-Freezer-III-Pro-360-A-RGB/ACFRE00184A',
      'coolerType', 'LIQUID_AIO',
      'radiatorSizeMm', 360,
      'radiatorLengthMm', 398,
      'radiatorWidthMm', 120,
      'radiatorThicknessMm', 38,
      'socketSupport', to_jsonb(ARRAY['AM5'::text, 'AM4'::text, 'LGA1851'::text, 'LGA1700'::text]),
      'depthMm', 398,
      'widthMm', 120,
      'heightMm', 38
    )),
    ('COOLER', '%ASSASSIN IV%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.deepcool.com/company/pressroom/newsrelease/2023/17380.shtml',
      'coolerType', 'AIR',
      'coolerHeightMm', 164,
      'heatpipeCount', 7,
      'socketSupport', to_jsonb(ARRAY['AM5'::text, 'AM4'::text, 'LGA1851'::text, 'LGA1700'::text, 'LGA1200'::text, 'LGA115X'::text]),
      'depthMm', 144,
      'widthMm', 147,
      'heightMm', 164
    )),
    ('COOLER', '%NH-D15 G2%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.noctua.at/en/products/nh-d15-g2-chromax-black/specifications',
      'coolerType', 'AIR',
      'coolerHeightMm', 168,
      'socketSupport', to_jsonb(ARRAY['AM5'::text, 'AM4'::text, 'LGA1851'::text, 'LGA1700'::text, 'LGA1200'::text, 'LGA115X'::text]),
      'depthMm', 152,
      'widthMm', 150,
      'heightMm', 168
    )),
    ('COOLER', '%TITAN 360%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.corsair.com/us/en/p/cpu-coolers/cw-9061018-ww/icue-link-titan-360-rx-rgb-aio-liquid-cpu-cooler-cw-9061018-ww',
      'coolerType', 'LIQUID_AIO',
      'radiatorSizeMm', 360,
      'radiatorLengthMm', 396,
      'radiatorWidthMm', 120,
      'radiatorThicknessMm', 27,
      'socketSupport', to_jsonb(ARRAY['AM5'::text, 'AM4'::text, 'LGA1851'::text, 'LGA1700'::text]),
      'depthMm', 396,
      'widthMm', 120,
      'heightMm', 27
    )),
    ('COOLER', '%KRAKEN ELITE%360%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://nzxt.com/products/kraken-360-elite-rgb-1',
      'coolerType', 'LIQUID_AIO',
      'radiatorSizeMm', 360,
      'radiatorLengthMm', 401,
      'radiatorWidthMm', 120,
      'radiatorThicknessMm', 27,
      'socketSupport', to_jsonb(ARRAY['AM5'::text, 'AM4'::text, 'LGA1851'::text, 'LGA1700'::text]),
      'depthMm', 401,
      'widthMm', 120,
      'heightMm', 27
    )),
    ('PSU', '%RM%', 80, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.kitguru.net/components/power-supplies/zardon/corsair-rm1000x-atx-v3-1-3rd-gen-2024-review/all/1/',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 160,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%HX1200I%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.corsair.com/us/en/p/psu/cp-9020281-na/hx1200i-fully-modular-ultra-low-noise-platinum-atx-1200-watt-pc-power-supply-cp-9020281-na',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 180,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%FOCUS%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://seasonic.com/focus-gx-atx-3/',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 140,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%GX-%', 80, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://seasonic.com/focus-gx-atx-3/',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 140,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%VERTEX%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://seasonic.com/vertex-gx/',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 160,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%HYDRO%', 80, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.tomshardware.com/reviews/fsp-hydro-g-pro-1000w-atx-v30-power-supply-review',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 150,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%MEGA GM%', 80, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.tomshardware.com/reviews/fsp-hydro-g-pro-1000w-atx-v30-power-supply-review',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 150,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%VIC GM%', 80, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.tomshardware.com/reviews/fsp-hydro-g-pro-1000w-atx-v30-power-supply-review',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 150,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%LEADEX%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://hwbusters.com/psus/super-flower-leadex-vii-pro-1000w-atx-v3-1-psu-review/',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 150,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%A850GS%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Power-Supply/MPG-A850GS-PCIE5',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 150,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%A850GN%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Power-Supply/MAG-A850GN-PCIE5',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 150,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%A1000G%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Power-Supply/MPG-A1000G-PCIE5',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 150,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%A750GL%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Power-Supply/MAG-A750GL-PCIE5',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 140,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('PSU', '%MWE GOLD%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.coolermaster.com/en-global/products/mwe-gold-850-v3-atx-3-1/',
      'atxSpec', 'ATX 3.1',
      'pcieSpec', 'PCIe 5.1',
      'gpuConnector', '12V-2x6',
      'depthMm', 160,
      'widthMm', 150,
      'heightMm', 86
    )),
    ('GPU', '%ROG ASTRAL%5090%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://rog.asus.com/graphics-cards/graphics-cards/rog-astral/rog-astral-rtx5090-o32g-gaming/spec/',
      'lengthMm', 357.6,
      'widthMm', 149.3,
      'heightMm', 76,
      'slotWidth', 3.8,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%SUPRIM%5090%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5090-32G-SUPRIM-SOC/Specification',
      'lengthMm', 359,
      'widthMm', 150,
      'heightMm', 76,
      'slotWidth', 3.8,
      'wattage', 575,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%5090%슈프림%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5090-32G-SUPRIM-SOC/Specification',
      'lengthMm', 359,
      'widthMm', 150,
      'heightMm', 76,
      'slotWidth', 3.8,
      'wattage', 575,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%MASTER%5090%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.aorus.com/graphics-cards/GV-N5090AORUS-M-32GD/Specification',
      'lengthMm', 360,
      'widthMm', 150,
      'heightMm', 75,
      'slotWidth', 3.7,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%ZOTAC%5090%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5090-solid-oc',
      'lengthMm', 329.7,
      'widthMm', 137.8,
      'heightMm', 67.8,
      'slotWidth', 3.5,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%조텍%5090%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5090-solid-oc',
      'lengthMm', 329.7,
      'widthMm', 137.8,
      'heightMm', 67.8,
      'slotWidth', 3.5,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%VANGUARD%5080%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5080-16G-VANGUARD-SOC/Specification',
      'lengthMm', 357,
      'widthMm', 151,
      'heightMm', 66,
      'slotWidth', 3.3,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%5080%뱅가드%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5080-16G-VANGUARD-SOC/Specification',
      'lengthMm', 357,
      'widthMm', 151,
      'heightMm', 66,
      'slotWidth', 3.3,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%MASTER%5080%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.aorus.com/graphics-cards/GV-N5080AORUSM-ICE-16GD/Specification',
      'lengthMm', 360,
      'widthMm', 150,
      'heightMm', 75,
      'slotWidth', 3.7,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%PRIME%5070%TI%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.asus.com/us/motherboards-components/graphics-cards/prime/prime-rtx5070ti-16g/techspec/',
      'lengthMm', 304,
      'widthMm', 126,
      'heightMm', 50,
      'slotWidth', 2.5,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%VENTUS%5060 TI%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5060-Ti-16G-VENTUS-2X-OC-PLUS/Specification',
      'lengthMm', 227,
      'widthMm', 126,
      'heightMm', 41,
      'slotWidth', 2.0,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%5060 TI%벤투스%', 100, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5060-Ti-16G-VENTUS-2X-OC-PLUS/Specification',
      'lengthMm', 227,
      'widthMm', 126,
      'heightMm', 41,
      'slotWidth', 2.0,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%ZOTAC%5060%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5060-twin-edge-oc',
      'lengthMm', 220.5,
      'slotWidth', 2.0,
      'powerConnector', '12V-2x6'
    )),
    ('GPU', '%조텍%5060%', 90, jsonb_build_object(
      'specSource', 'MANUAL_PRODUCT_SPEC',
      'specConfidence', 'VERIFIED_FIXED_SPEC',
      'specReferenceUrl', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5060-twin-edge-oc',
      'lengthMm', 220.5,
      'slotWidth', 2.0,
      'powerConnector', '12V-2x6'
    ))
),
matched AS (
  SELECT DISTINCT ON (p.id) p.id, v.specs
  FROM parts p
  JOIN verified_specs v
    ON p.category = v.category
   AND upper(p.name) LIKE v.name_pattern
  WHERE p.deleted_at IS NULL
    AND p.status = 'ACTIVE'
  ORDER BY p.id, v.priority DESC
)
UPDATE parts p
SET attributes = jsonb_strip_nulls(p.attributes || m.specs || jsonb_build_object('toolReady', true)),
    updated_at = now()
FROM matched m
WHERE p.id = m.id;
