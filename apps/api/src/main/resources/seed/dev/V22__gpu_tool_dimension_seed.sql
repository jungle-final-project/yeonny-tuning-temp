-- Make collected RTX 50-series GPU assets usable by compatibility and size tools.
-- Prices/images stay in offer snapshots; this migration only fixes stable board dimensions.

WITH gpu_dimension_seed (
  public_id,
  length_mm,
  width_mm,
  height_mm,
  slot_width,
  power_connector,
  spec_reference_url,
  spec_note
) AS (
  VALUES
    -- ZOTAC
    ('88622262-a225-456b-b8f1-ae9914d20f70', 220.5, 120.25, 41.6, 2.0, '8-pin', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5060-twin-edge-oc', 'ZOTAC RTX 5060 Twin Edge OC family'),
    ('eda3d521-af8a-4250-b15a-38508adba422', 220.5, 120.25, 41.6, 2.0, '8-pin', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5060-ti-twin-edge-oc', 'ZOTAC RTX 5060 Ti Twin Edge OC family'),
    ('92468296-2632-42ab-8b47-fd1c20cd4690', 304.4, 115.8, 41.6, 2.0, '12V-2x6', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5070-amp-white-edition', 'ZOTAC RTX 5070 AMP White family'),
    ('68481a42-7f6a-4bb4-8a33-92033dfcc13b', 304.4, 115.8, 41.6, 2.0, '12V-2x6', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5070-solid-oc', 'ZOTAC RTX 5070 SOLID OC family'),
    ('1c1fffa6-07db-478e-8dfc-ae8823fd8227', 332.1, 137.5, 69.6, 3.5, '12V-2x6', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5070-ti-amp-extreme-infinity', 'ZOTAC RTX 5070 Ti AMP Extreme Infinity family'),
    ('f45d9f83-85f4-4cb9-8fe7-8a510adf02f9', 303.5, 115.8, 55.7, 2.5, '12V-2x6', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5070-ti-solid-core-oc-white-edition', 'ZOTAC RTX 5070 Ti SOLID CORE OC White family'),
    ('e22b70c2-2c12-4dac-91de-c05f3b0e40c8', 303.5, 115.8, 55.7, 2.5, '12V-2x6', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5070-ti-solid-oc', 'ZOTAC RTX 5070 Ti SOLID OC family'),
    ('b3c9a4e2-4ef0-40a7-b081-6af3171b61a6', 332.1, 137.5, 69.6, 3.5, '12V-2x6', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5080-amp-extreme-infinity', 'ZOTAC RTX 5080 AMP Extreme Infinity family'),
    ('2c105de4-d866-47cf-969e-13a23ed09c41', 329.7, 137.8, 67.8, 3.5, '12V-2x6', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5080-solid-core-oc', 'ZOTAC RTX 5080 SOLID CORE OC family'),
    ('a7d054a6-e729-4e7d-bfa5-0ae580e65c58', 329.7, 137.8, 67.8, 3.5, '12V-2x6', 'https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5080-solid-oc', 'ZOTAC RTX 5080 SOLID OC family'),

    -- GIGABYTE
    ('e806eee2-37ad-4e61-b21a-51c4f9945e65', 208.0, 120.0, 40.0, 2.0, '8-pin', 'https://www.gigabyte.com/Graphics-Card/GV-N506TWF2MAX-OC-8GD', 'GIGABYTE RTX 5060 Ti WINDFORCE MAX OC family'),
    ('cca9526d-1f92-4dda-9bad-fbfbfdf387ea', 281.0, 117.0, 50.0, 2.5, '8-pin', 'https://www.gigabyte.com/Graphics-Card/GV-N506TAERO-OC-8GD', 'GIGABYTE RTX 5060 Ti AERO OC family'),
    ('c10b1401-557a-410b-b15d-a0ef4c2aa415', 208.0, 120.0, 40.0, 2.0, '8-pin', 'https://www.gigabyte.com/Graphics-Card/GV-N506TWF2MAX-OC-8GD', 'GIGABYTE RTX 5060 Ti WINDFORCE MAX OC family'),
    ('f66d47e2-08fa-4363-86e8-5008ca2bd813', 208.0, 120.0, 40.0, 2.0, '8-pin', 'https://www.gigabyte.com/Graphics-Card/GV-N5060WF2MAX-OC-8GD', 'GIGABYTE RTX 5060 WINDFORCE MAX OC family'),
    ('5cc4ce8b-eb26-490f-b481-d8dcb4e1b3bb', 250.0, 110.0, 50.0, 2.5, '12V-2x6', 'https://www.gigabyte.com/Graphics-Card/GV-N5070EAGLEOC-SFF-12GD', 'GIGABYTE RTX 5070 EAGLE OC SFF family'),
    ('50c6478e-7c5f-4cea-aa07-65bb20e82559', 340.0, 140.0, 70.0, 3.0, '12V-2x6', 'https://www.gigabyte.com/Graphics-Card/GV-N507TAERO-OC-16GD', 'GIGABYTE RTX 5070 Ti AERO OC family'),
    ('39951f24-1313-4cf4-a4e8-ac1c8757a3e4', 340.0, 140.0, 70.0, 3.0, '12V-2x6', 'https://www.gigabyte.com/Graphics-Card/GV-N507TAERO-OC-16GD', 'GIGABYTE RTX 5070 Ti AERO OC family'),
    ('460f7d37-bd23-4bcf-9786-d9c68126a77c', 304.0, 126.0, 50.0, 2.5, '12V-2x6', 'https://www.gigabyte.com/Graphics-Card/GV-N507TWF3OC-SFF-16GD', 'GIGABYTE RTX 5070 Ti WINDFORCE OC SFF family'),
    ('dcc58534-930e-4a3a-84b3-a5a723723b4e', 304.0, 126.0, 50.0, 2.5, '12V-2x6', 'https://www.gigabyte.com/Graphics-Card/GV-N5080AERO-OC-SFF-16GD', 'GIGABYTE RTX 5080 AERO OC SFF family'),
    ('c5bb0010-6175-49d3-8be1-a962629e0081', 340.0, 140.0, 70.0, 3.0, '12V-2x6', 'https://www.gigabyte.com/Graphics-Card/GV-N5080GAMING-OC-16GD', 'GIGABYTE RTX 5080 GAMING OC family'),
    ('15205cdd-15d5-422b-9226-0287798c98be', 360.0, 150.0, 75.0, 3.7, '12V-2x6', 'https://www.aorus.com/graphics-cards/GV-N5080AORUSM-ICE-16GD/Specification', 'GIGABYTE AORUS RTX 5080 MASTER ICE family'),
    ('abfe2826-ddb7-4ec2-8ccc-223d1cf5346f', 340.0, 150.0, 70.0, 3.5, '12V-2x6', 'https://www.gigabyte.com/Graphics-Card/GV-N5090GAMING-OC-32GD', 'GIGABYTE RTX 5090 GAMING OC family'),
    ('27adbaac-3b5a-47ef-ae9d-1f53e2d7a9c5', 360.0, 150.0, 75.0, 3.7, '12V-2x6', 'https://www.aorus.com/graphics-cards/GV-N5090AORUS-M-32GD/Specification', 'GIGABYTE AORUS RTX 5090 MASTER family'),
    ('bd83e094-18e7-491c-bb9a-40fb81d43041', 360.0, 150.0, 75.0, 3.7, '12V-2x6', 'https://www.aorus.com/graphics-cards/GV-N5090AORUS-M-ICE-32GD/Specification', 'GIGABYTE AORUS RTX 5090 MASTER ICE family'),

    -- ASUS
    ('805d3367-c327-453e-96ad-60f7c957e69d', 228.0, 123.0, 50.0, 2.5, '8-pin', 'https://www.asus.com/motherboards-components/graphics-cards/dual/dual-rtx5060-o8g/techspec/', 'ASUS DUAL RTX 5060 EVO/OC family'),
    ('d5834448-23f7-49d6-8e29-56ab8e939f71', 228.0, 123.0, 50.0, 2.5, '8-pin', 'https://www.asus.com/motherboards-components/graphics-cards/dual/dual-rtx5060-o8g/techspec/', 'ASUS DUAL RTX 5060 OC family'),
    ('1eae7ad3-cb3c-41fd-98a7-f83ca94070cf', 229.0, 120.0, 50.0, 2.5, '8-pin', 'https://www.asus.com/motherboards-components/graphics-cards/dual/dual-rtx5060ti-o8g/techspec/', 'ASUS DUAL RTX 5060 Ti family'),
    ('82162bae-b53b-4800-bde5-31f944468780', 229.0, 120.0, 50.0, 2.5, '8-pin', 'https://www.asus.com/motherboards-components/graphics-cards/dual/dual-rtx5060ti-o8g/techspec/', 'ASUS DUAL RTX 5060 Ti OC family'),
    ('870bf708-6460-4ed0-9338-de74a69b3e86', 229.0, 120.0, 50.0, 2.5, '8-pin', 'https://www.asus.com/motherboards-components/graphics-cards/dual/dual-rtx5060ti-o8g/techspec/', 'ASUS DUAL RTX 5060 Ti OC family'),
    ('695fb5ec-028b-4850-9ed0-bb25ab7bea44', 304.0, 126.0, 50.0, 2.5, '12V-2x6', 'https://www.asus.com/motherboards-components/graphics-cards/prime/prime-rtx5080-o16g/techspec/', 'ASUS PRIME RTX 5080 OC family'),
    ('1fab808e-5dec-462c-91ca-91b4485b169e', 357.6, 149.3, 76.0, 3.8, '12V-2x6', 'https://rog.asus.com/graphics-cards/graphics-cards/rog-astral/rog-astral-rtx5080-o16g-gaming/spec/', 'ASUS ROG Astral RTX 5080 OC family'),
    ('a91e68f1-d549-48dc-9fa7-51d29a08b3d8', 357.6, 149.3, 76.0, 3.8, '12V-2x6', 'https://rog.asus.com/graphics-cards/graphics-cards/rog-astral/rog-astral-rtx5080-o16g-white/spec/', 'ASUS ROG Astral RTX 5080 White OC family'),
    ('a2f9d478-b368-4c16-9048-4a064828c5c1', 357.6, 149.3, 76.0, 3.8, '12V-2x6', 'https://rog.asus.com/graphics-cards/graphics-cards/rog-matrix/rog-matrix-rtx5090-p32g-gaming/spec/', 'ASUS ROG MATRIX RTX 5090 OC family'),
    ('3f8afe18-369c-4f2b-b656-fe95e5416975', 229.0, 120.0, 50.0, 2.5, '8-pin', 'https://www.asus.com/motherboards-components/graphics-cards/prime/prime-rtx5060ti-o16g/techspec/', 'ASUS PRIME RTX 5060 Ti 16GB SFF family'),

    -- MSI
    ('2e21f9a8-45ad-4a70-b9e6-8ecbbe3dd3ef', 197.0, 120.0, 41.0, 2.0, '8-pin', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5060-8G-VENTUS-2X-OC/Specification', 'MSI RTX 5060 VENTUS 2X OC family'),
    ('8482e6fd-a1a2-4eee-856d-a9ac1e796789', 197.0, 120.0, 41.0, 2.0, '8-pin', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5060-8G-SHADOW-2X-OC/Specification', 'MSI RTX 5060 SHADOW 2X OC family'),
    ('a76ff652-7c33-4640-b7ee-beb3c82c6109', 338.0, 140.0, 50.0, 3.0, '12V-2x6', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5070-12G-GAMING-TRIO-OC/Specification', 'MSI RTX 5070 GAMING TRIO OC family'),
    ('e8ca30c4-7cea-40d4-a3a4-3b4bc79ab715', 303.0, 121.0, 50.0, 3.0, '12V-2x6', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5070-Ti-16G-VENTUS-3X-OC/Specification', 'MSI RTX 5070 Ti VENTUS 3X OC family'),
    ('17725485-dc95-44a8-b810-ecb2378f74a0', 338.0, 140.0, 50.0, 3.0, '12V-2x6', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5070-Ti-16G-GAMING-TRIO-OC/Specification', 'MSI RTX 5070 Ti GAMING TRIO OC family'),
    ('4f5b001d-3f1b-4e57-9c42-573d308d1193', 338.0, 140.0, 50.0, 3.0, '12V-2x6', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5070-Ti-16G-GAMING-TRIO-OC/Specification', 'MSI RTX 5070 Ti GAMING TRIO OC family'),
    ('4f615852-d0ec-4d05-9353-bc5d26906e5b', 303.0, 121.0, 50.0, 3.0, '12V-2x6', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5080-16G-SHADOW-3X-OC/Specification', 'MSI RTX 5080 SHADOW 3X OC family'),
    ('82525758-bf8a-4224-827e-7c4f659765ea', 338.0, 140.0, 71.0, 3.5, '12V-2x6', 'https://www.msi.com/Graphics-Card/GeForce-RTX-5090-32G-GAMING-TRIO-OC/Specification', 'MSI RTX 5090 GAMING TRIO OC family'),

    -- PNY
    ('b7ba7bdd-2465-44ec-9f28-7a3835c5f7ac', 340.0, 140.0, 70.0, 3.5, '12V-2x6', 'https://www.pny.com/pny-geforce-rtx-5090-32gb-triple-fan', 'PNY RTX 5090 Triple Fan family')
)
UPDATE parts p
SET attributes = jsonb_strip_nulls(
      p.attributes
      || jsonb_build_object(
        'lengthMm', g.length_mm,
        'widthMm', g.width_mm,
        'heightMm', g.height_mm,
        'dimensionsMm', jsonb_build_object('depth', g.length_mm, 'width', g.width_mm, 'height', g.height_mm),
        'slotWidth', g.slot_width,
        'powerConnector', g.power_connector,
        'toolReady', true,
        'specSource', 'MANUAL_PRODUCT_SPEC',
        'specConfidence', 'VERIFIED_OR_FAMILY_FIXED_SPEC',
        'specReferenceUrl', g.spec_reference_url,
        'toolReviewReason', 'GPU dimensions manually filled for Tool use: ' || g.spec_note,
        'metadataVersion', 7
      )
    ),
    updated_at = now()
FROM gpu_dimension_seed g
WHERE p.public_id = g.public_id::uuid
  AND p.category = 'GPU'
  AND p.status = 'ACTIVE'
  AND p.deleted_at IS NULL;
