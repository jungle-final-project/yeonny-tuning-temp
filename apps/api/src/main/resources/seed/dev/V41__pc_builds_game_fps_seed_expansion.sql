-- Expand game FPS evidence with source-verified PC-Builds rows.
-- PC-Builds FPS calculator rows store avg_fps plus source min/max in metadata.
-- They are not one_percent_low_fps measurements.

WITH fps_seed AS (
  SELECT *
  FROM (VALUES
    -- Exact FPS calculator pages: AMD Ryzen 9 9950X3D + selected RTX GPU.
    ('00000000-0000-4000-8000-000000041001'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 209.00, 178.00, 240.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1Bm0jh/ryzen-9-9950x3d/geforce-rtx-5060-ti/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5060 Ti', 'RYZEN_9_9950X3D', 'RTX_5060_TI', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041002'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 140.00, 119.00, 161.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1Bm0jh/ryzen-9-9950x3d/geforce-rtx-5060-ti/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5060 Ti', 'RYZEN_9_9950X3D', 'RTX_5060_TI', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041003'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, 'c10b1401-557a-410b-b15d-a0ef4c2aa415'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 80.00, 68.00, 92.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1Bm0jh/ryzen-9-9950x3d/geforce-rtx-5060-ti/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5060 Ti', 'RYZEN_9_9950X3D', 'RTX_5060_TI', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041004'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', NULL::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 267.00, 227.00, 307.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1cl1xO0jh/core-i7-12700k/geforce-rtx-5070/pubg-battlegrounds/', 'Core i7-12700K', 'GeForce RTX 5070', NULL, 'RTX_5070', 'FPS_CALCULATOR_GPU_CLASS'),
    ('00000000-0000-4000-8000-000000041005'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', NULL::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 179.00, 153.00, 206.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1cl1xO0jh/core-i7-12700k/geforce-rtx-5070/pubg-battlegrounds/', 'Core i7-12700K', 'GeForce RTX 5070', NULL, 'RTX_5070', 'FPS_CALCULATOR_GPU_CLASS'),
    ('00000000-0000-4000-8000-000000041006'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', NULL::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 102.00, 87.00, 118.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1cl1xO0jh/core-i7-12700k/geforce-rtx-5070/pubg-battlegrounds/', 'Core i7-12700K', 'GeForce RTX 5070', NULL, 'RTX_5070', 'FPS_CALCULATOR_GPU_CLASS'),
    ('00000000-0000-4000-8000-000000041007'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 332.00, 282.00, 381.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0jh/ryzen-9-9950x3d/geforce-rtx-5080/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041008'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 223.00, 189.00, 256.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0jh/ryzen-9-9950x3d/geforce-rtx-5080/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041009'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 127.00, 108.00, 146.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0jh/ryzen-9-9950x3d/geforce-rtx-5080/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041010'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 362.00, 308.00, 416.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1ul0jh/ryzen-9-9950x3d/geforce-rtx-5090/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5090', 'RYZEN_9_9950X3D', 'RTX_5090', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041011'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 243.00, 207.00, 280.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1ul0jh/ryzen-9-9950x3d/geforce-rtx-5090/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5090', 'RYZEN_9_9950X3D', 'RTX_5090', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041012'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '9f3d289c-6739-459c-9e79-5a1417165ded'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 139.00, 118.00, 160.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1ul0jh/ryzen-9-9950x3d/geforce-rtx-5090/pubg-battlegrounds/', 'Ryzen 9 9950X3D', 'GeForce RTX 5090', 'RYZEN_9_9950X3D', 'RTX_5090', 'FPS_CALCULATOR_EXACT_PARTS'),

    ('00000000-0000-4000-8000-000000041013'::uuid, 'Valorant', 'valorant', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 614.00, 522.00, 707.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh01W/ryzen-9-9950x3d/geforce-rtx-5080/valorant/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041014'::uuid, 'Valorant', 'valorant', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 583.00, 495.00, 670.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh01W/ryzen-9-9950x3d/geforce-rtx-5080/valorant/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041015'::uuid, 'Valorant', 'valorant', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 541.00, 460.00, 622.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh01W/ryzen-9-9950x3d/geforce-rtx-5080/valorant/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041016'::uuid, 'Overwatch 2', 'overwatch-2', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 587.00, 499.00, 675.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0jn/ryzen-9-9950x3d/geforce-rtx-5080/overwatch-2/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041017'::uuid, 'Overwatch 2', 'overwatch-2', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 380.00, 323.00, 436.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0jn/ryzen-9-9950x3d/geforce-rtx-5080/overwatch-2/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041018'::uuid, 'Overwatch 2', 'overwatch-2', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 205.00, 174.00, 236.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0jn/ryzen-9-9950x3d/geforce-rtx-5080/overwatch-2/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041019'::uuid, 'Lost Ark', 'lost-ark', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 259.00, 220.00, 298.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0ax/ryzen-9-9950x3d/geforce-rtx-5080/lost-ark/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041020'::uuid, 'Lost Ark', 'lost-ark', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 175.00, 149.00, 201.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0ax/ryzen-9-9950x3d/geforce-rtx-5080/lost-ark/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041021'::uuid, 'Lost Ark', 'lost-ark', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 101.00, 86.00, 116.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh0ax/ryzen-9-9950x3d/geforce-rtx-5080/lost-ark/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041022'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 137.00, 117.00, 158.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh02g/ryzen-9-9950x3d/geforce-rtx-5080/cyberpunk-2077/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041023'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 106.00, 90.00, 121.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh02g/ryzen-9-9950x3d/geforce-rtx-5080/cyberpunk-2077/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000041024'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', 'a75d6544-2296-4c4c-a7cd-64596e66f6d7'::uuid, '4f615852-d0ec-4d05-9353-bc5d26906e5b'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 70.00, 59.00, 80.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1HD1uh02g/ryzen-9-9950x3d/geforce-rtx-5080/cyberpunk-2077/', 'Ryzen 9 9950X3D', 'GeForce RTX 5080', 'RYZEN_9_9950X3D', 'RTX_5080', 'FPS_CALCULATOR_EXACT_PARTS'),

    -- GPU pages: popular game averages with Ryzen 7 9800X3D shown by PC-Builds.
    ('00000000-0000-4000-8000-000000041025'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, 'FHD', 'PC_BUILDS_GPU_POPULAR', 501.00, 426.00, 576.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1sm/nvidia-geforce-rtx-5060', 'Ryzen 7 9800X3D', 'GeForce RTX 5060', 'RYZEN_7_9800X3D', 'RTX_5060', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041026'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, 'QHD', 'PC_BUILDS_GPU_POPULAR', 476.00, 405.00, 547.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1sm/nvidia-geforce-rtx-5060', 'Ryzen 7 9800X3D', 'GeForce RTX 5060', 'RYZEN_7_9800X3D', 'RTX_5060', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041027'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, '4K', 'PC_BUILDS_GPU_POPULAR', 442.00, 376.00, 509.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1sm/nvidia-geforce-rtx-5060', 'Ryzen 7 9800X3D', 'GeForce RTX 5060', 'RYZEN_7_9800X3D', 'RTX_5060', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041028'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, 'FHD', 'PC_BUILDS_GPU_POPULAR', 112.00, 95.00, 128.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1sm/nvidia-geforce-rtx-5060', 'Ryzen 7 9800X3D', 'GeForce RTX 5060', 'RYZEN_7_9800X3D', 'RTX_5060', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041029'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, 'QHD', 'PC_BUILDS_GPU_POPULAR', 86.00, 73.00, 99.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1sm/nvidia-geforce-rtx-5060', 'Ryzen 7 9800X3D', 'GeForce RTX 5060', 'RYZEN_7_9800X3D', 'RTX_5060', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041030'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, '4K', 'PC_BUILDS_GPU_POPULAR', 57.00, 48.00, 66.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1sm/nvidia-geforce-rtx-5060', 'Ryzen 7 9800X3D', 'GeForce RTX 5060', 'RYZEN_7_9800X3D', 'RTX_5060', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041031'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, 'FHD', 'PC_BUILDS_GPU_POPULAR', 617.00, 524.00, 710.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1xO/nvidia-geforce-rtx-5070', 'Ryzen 7 9800X3D', 'GeForce RTX 5070', 'RYZEN_7_9800X3D', 'RTX_5070', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041032'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, 'QHD', 'PC_BUILDS_GPU_POPULAR', 585.00, 497.00, 673.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1xO/nvidia-geforce-rtx-5070', 'Ryzen 7 9800X3D', 'GeForce RTX 5070', 'RYZEN_7_9800X3D', 'RTX_5070', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041033'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, '4K', 'PC_BUILDS_GPU_POPULAR', 544.00, 462.00, 625.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1xO/nvidia-geforce-rtx-5070', 'Ryzen 7 9800X3D', 'GeForce RTX 5070', 'RYZEN_7_9800X3D', 'RTX_5070', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041034'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, 'FHD', 'PC_BUILDS_GPU_POPULAR', 138.00, 118.00, 159.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1xO/nvidia-geforce-rtx-5070', 'Ryzen 7 9800X3D', 'GeForce RTX 5070', 'RYZEN_7_9800X3D', 'RTX_5070', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041035'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, 'QHD', 'PC_BUILDS_GPU_POPULAR', 106.00, 90.00, 121.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1xO/nvidia-geforce-rtx-5070', 'Ryzen 7 9800X3D', 'GeForce RTX 5070', 'RYZEN_7_9800X3D', 'RTX_5070', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041036'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, 'a76ff652-7c33-4640-b7ee-beb3c82c6109'::uuid, 32, '4K', 'PC_BUILDS_GPU_POPULAR', 70.00, 60.00, 81.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1xO/nvidia-geforce-rtx-5070', 'Ryzen 7 9800X3D', 'GeForce RTX 5070', 'RYZEN_7_9800X3D', 'RTX_5070', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041037'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, 'FHD', 'PC_BUILDS_GPU_POPULAR', 693.00, 589.00, 797.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1wO/nvidia-geforce-rtx-5070-ti', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041038'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, 'QHD', 'PC_BUILDS_GPU_POPULAR', 657.00, 558.00, 756.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1wO/nvidia-geforce-rtx-5070-ti', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041039'::uuid, 'Valorant', 'valorant', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, '4K', 'PC_BUILDS_GPU_POPULAR', 611.00, 519.00, 702.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1wO/nvidia-geforce-rtx-5070-ti', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041040'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, 'FHD', 'PC_BUILDS_GPU_POPULAR', 155.00, 132.00, 178.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1wO/nvidia-geforce-rtx-5070-ti', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041041'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, 'QHD', 'PC_BUILDS_GPU_POPULAR', 119.00, 101.00, 137.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1wO/nvidia-geforce-rtx-5070-ti', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'GPU_PAGE_POPULAR_GAME'),
    ('00000000-0000-4000-8000-000000041042'::uuid, 'Cyberpunk 2077', 'cyberpunk-2077', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, '4K', 'PC_BUILDS_GPU_POPULAR', 79.00, 67.00, 91.00, 'PC-Builds GPU popular game table', 'https://pc-builds.com/gpu/1wO/nvidia-geforce-rtx-5070-ti', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'GPU_PAGE_POPULAR_GAME')
  ) AS seed(public_id, game_title, game_key, cpu_public_id, gpu_public_id, ram_gb, resolution, graphics_preset, avg_fps, source_min_fps, source_max_fps, source_name, source_url, source_cpu_name, source_gpu_name, cpu_class, gpu_class, source_metric_type)
)
INSERT INTO game_fps_benchmarks (
  public_id,
  game_title,
  game_key,
  cpu_part_id,
  gpu_part_id,
  ram_gb,
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
       cpu.id,
       gpu.id,
       seed.ram_gb,
       seed.resolution,
       seed.graphics_preset,
       seed.avg_fps,
       NULL,
       seed.source_name,
       seed.source_url,
       '2026-07-01'::date,
       CASE WHEN seed.source_metric_type = 'FPS_CALCULATOR_EXACT_PARTS' THEN 'MEDIUM' ELSE 'LOW' END,
       jsonb_strip_nulls(jsonb_build_object(
         'aliases', CASE seed.game_key
           WHEN 'pubg' THEN jsonb_build_array('배그', 'pubg', 'battlegrounds', 'playerunknowns battlegrounds')
           WHEN 'valorant' THEN jsonb_build_array('발로란트', '발로', 'valorant')
           WHEN 'overwatch-2' THEN jsonb_build_array('오버워치', '오버워치2', 'overwatch', 'overwatch 2')
           WHEN 'lost-ark' THEN jsonb_build_array('로스트아크', '로아', 'lost ark')
           WHEN 'cyberpunk-2077' THEN jsonb_build_array('사이버펑크', '사펑', 'cyberpunk', 'cyberpunk 2077')
           ELSE jsonb_build_array(seed.game_key)
         END,
         'sourceCpuName', seed.source_cpu_name,
         'sourceGpuName', seed.source_gpu_name,
         'cpuClass', seed.cpu_class,
         'gpuClass', seed.gpu_class,
         'sourceResolutionText', seed.resolution,
         'sourcePresetText', seed.graphics_preset,
         'sourceMinFps', seed.source_min_fps,
         'sourceMaxFps', seed.source_max_fps,
         'sourceMetricType', seed.source_metric_type,
         'sourceAccessMethod', 'MANUAL_PAGE_READ',
         'sourceCapturedText', concat(seed.resolution, ' avg ', seed.avg_fps, ', min ', seed.source_min_fps, ', max ', seed.source_max_fps),
         'driverVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'gameVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'osVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'testScene', 'UNKNOWN_PUBLIC_SOURCE',
         'sampleCount', 'UNKNOWN_PUBLIC_SOURCE',
         'upscaling', 'UNKNOWN_PUBLIC_SOURCE',
         'frameGeneration', 'UNKNOWN_PUBLIC_SOURCE',
         'rayTracing', CASE WHEN seed.game_key = 'cyberpunk-2077' THEN 'SOURCE_PAGE_DEFAULT_UNKNOWN' ELSE 'NOT_SPECIFIED' END,
         'evidenceExactness', seed.source_metric_type,
         'qualityGaps', jsonb_build_array(
           'one_percent_low_fps_not_provided',
           'driver_version_not_provided',
           'game_version_not_provided',
           'test_scene_not_provided'
         ),
         'guaranteePolicy', 'NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE',
         'notes', 'Public FPS reference for recommendation evidence. Do not present as guaranteed FPS.'
       )),
       now(),
       now()
FROM fps_seed seed
LEFT JOIN parts cpu ON seed.cpu_public_id IS NOT NULL AND cpu.public_id = seed.cpu_public_id
JOIN parts gpu ON gpu.public_id = seed.gpu_public_id
ON CONFLICT DO NOTHING;

DROP VIEW IF EXISTS game_fps_coverage_gaps;
DROP VIEW IF EXISTS game_fps_coverage_status;

CREATE OR REPLACE VIEW game_fps_coverage_status AS
SELECT target.public_id::text AS target_id,
       target.target_type,
       target.game_title,
       target.game_key,
       target.resolution,
       target.graphics_preset,
       target.gpu_class,
       target.cpu_class,
       target.priority,
       target.status AS target_status,
       coalesce(match_count.matched_rows, 0) AS matched_rows,
       coalesce(match_count.exact_preset_rows, 0) AS exact_preset_rows,
       CASE
         WHEN target.status = 'DEFERRED' THEN 'DEFERRED'
         WHEN coalesce(match_count.matched_rows, 0) > 0 THEN 'SEEDED'
         ELSE 'GAP'
       END AS coverage_status,
       target.reason,
       target.metadata
FROM game_fps_coverage_targets target
LEFT JOIN LATERAL (
  SELECT count(*) AS matched_rows,
         count(*) FILTER (
           WHERE target.graphics_preset IS NULL OR fps.graphics_preset = target.graphics_preset
         ) AS exact_preset_rows
  FROM game_fps_benchmarks fps
  WHERE fps.deleted_at IS NULL
    AND fps.game_key = target.game_key
    AND (target.resolution IS NULL OR fps.resolution = target.resolution)
    AND (target.gpu_class IS NULL OR fps.metadata->>'gpuClass' = target.gpu_class)
    AND (target.cpu_class IS NULL OR fps.metadata->>'cpuClass' = target.cpu_class)
) match_count ON true
WHERE target.deleted_at IS NULL;

CREATE OR REPLACE VIEW game_fps_coverage_gaps AS
SELECT *
FROM game_fps_coverage_status
WHERE coverage_status = 'GAP';
