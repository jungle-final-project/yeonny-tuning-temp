package com.buildgraph.prototype.part.tool;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.benchmark.BenchmarkQueryCached;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.query.PartQueryCachedLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ToolCheckService {
    private static final List<String> TOOL_ORDER = List.of("compatibility", "power", "size", "performance", "price");
    private final JdbcTemplate jdbcTemplate;
    private final PartQuery partQuery;
    private final BenchmarkQueryCached benchmarkQuery;

    @Autowired
    public ToolCheckService(JdbcTemplate jdbcTemplate, PartQuery partQuery, BenchmarkQueryCached benchmarkQuery) {
        this.jdbcTemplate = jdbcTemplate;
        this.partQuery = partQuery;
        this.benchmarkQuery = benchmarkQuery;
    }

    /* Tests use the same query path without retaining cache state between cases. */
    public ToolCheckService(JdbcTemplate jdbcTemplate) {
        this(
                jdbcTemplate,
                new PartQuery(jdbcTemplate, new PartQueryCachedLoader(new NoOpCacheManager(), jdbcTemplate)),
                new BenchmarkQueryCached(new NoOpCacheManager(), jdbcTemplate)
        );
    }

    /** Checks every MVP Tool against the same build candidate. */
    public List<Map<String, Object>> checkBuild(List<ToolBuildPart> parts, int budget) {
        return checkBuild(parts, budget, null);
    }

    /**
     * checkBuild with prefetched benchmark rows, so callers evaluating many builds at once
     * (예: builds/history)는 build마다 벤치마크를 조회하지 않는다(N+1 회피). null이면 기존처럼 조회한다.
     */
    public List<Map<String, Object>> checkBuild(List<ToolBuildPart> parts, int budget, Map<Long, Map<String, Object>> benchmarkRows) {
        return TOOL_ORDER.stream()
                .map(tool -> checkResolvedTool(tool, parts, budget, total(parts), Map.of(), benchmarkRows))
                .toList();
    }

    /** 여러 build 부품의 최신 벤치마크를 한 번에 로드한다 — 호출처가 checkBuild에 주입해 build별 조회를 없앤다. */
    public Map<Long, Map<String, Object>> loadLatestBenchmarks(List<ToolBuildPart> parts) {
        return latestBenchmarks(parts);
    }

    /** Runs one Tool API request through the shared validation engine. */
    public Map<String, Object> checkTool(String toolName, Map<String, Object> request) {
        String tool = normalizeToolName(toolName);
        Map<String, Object> body = request == null ? Map.of() : request;
        List<ToolBuildPart> parts = resolveParts(body);
        if (parts.isEmpty()) {
            return seedBackedToolResult(tool);
        }
        Map<String, Object> context = objectMap(body.get("context"));
        int currentTotalPrice = firstNumber(context.get("currentTotalPrice"), total(parts));
        int budget = firstNumber(context.get("budget"), currentTotalPrice);
        return checkResolvedTool(tool, parts, budget, currentTotalPrice, context);
    }

    /** Runs Agent-requested Tool checks from the Agent root resource. */
    public List<Map<String, Object>> checkAgentTools(String rootType, String rootId, List<String> toolNames) {
        List<String> tools = toolNames == null || toolNames.isEmpty() ? TOOL_ORDER : toolNames.stream().map(ToolCheckService::normalizeToolName).toList();
        AgentRootParts rootParts = resolveAgentRootParts(rootType, rootId);
        if (rootParts.parts().isEmpty()) {
            return tools.stream().map(this::seedBackedToolResult).toList();
        }
        int total = total(rootParts.parts());
        int budget = rootParts.budget() == null || rootParts.budget() <= 0 ? total : rootParts.budget();
        Map<String, Object> context = MockData.map("rootType", rootType, "rootId", rootId);
        return tools.stream()
                .map(tool -> checkResolvedTool(tool, rootParts.parts(), budget, total, context))
                .toList();
    }

    /** Dispatches a normalized Tool name to its rule implementation. */
    private Map<String, Object> checkResolvedTool(
            String tool,
            List<ToolBuildPart> parts,
            int budget,
            int currentTotalPrice,
            Map<String, Object> context
    ) {
        return checkResolvedTool(tool, parts, budget, currentTotalPrice, context, null);
    }

    private Map<String, Object> checkResolvedTool(
            String tool,
            List<ToolBuildPart> parts,
            int budget,
            int currentTotalPrice,
            Map<String, Object> context,
            Map<Long, Map<String, Object>> prefetchedBenchmarks
    ) {
        Map<String, ToolBuildPart> byCategory = byCategory(parts);
        return switch (tool) {
            case "compatibility" -> compatibility(byCategory, parts);
            case "power" -> power(byCategory, parts);
            case "size" -> size(byCategory);
            case "performance" -> performance(byCategory, context, prefetchedBenchmarks);
            case "price" -> price(parts, budget, currentTotalPrice);
            default -> throw new IllegalArgumentException("지원하지 않는 Tool입니다: " + tool);
        };
    }

    /** Evaluates socket, memory, cooler support, cooler TDP, RAM form factor, and RAM slot capacity compatibility. */
    private Map<String, Object> compatibility(Map<String, ToolBuildPart> byCategory, List<ToolBuildPart> parts) {
        ToolBuildPart cpu = byCategory.get("CPU");
        ToolBuildPart motherboard = byCategory.get("MOTHERBOARD");
        ToolBuildPart cooler = byCategory.get("COOLER");
        boolean socketMatched = same(stringAttr(cpu, "socket"), stringAttr(motherboard, "socket"));
        // 메모리 규격 — RAM 전 행을 순회한다(스틱 수 검사와 같은 원칙: byCategory는 카테고리당 1개로 접힌다).
        // 결측이면 'DDR5' 같은 임의 기본값으로 비교하지 않는다 — 없는 데이터로 FAIL도, 근거 없는 PASS도 내지 않는다.
        String boardMemoryType = stringAttr(motherboard, "memoryType");
        LinkedHashSet<String> ramMemoryTypes = new LinkedHashSet<>();
        boolean ramPresent = false;
        boolean ramTypeMissing = false;
        for (ToolBuildPart part : parts) {
            if (!"RAM".equalsIgnoreCase(firstText(part.category(), ""))) {
                continue;
            }
            ramPresent = true;
            String memoryType = stringAttr(part, "memoryType");
            if (memoryType == null) {
                ramTypeMissing = true;
            } else {
                ramMemoryTypes.add(memoryType.toUpperCase(Locale.ROOT));
            }
        }
        List<String> mismatchedRamTypes = boardMemoryType == null ? List.of() : ramMemoryTypes.stream()
                .filter(type -> !type.equalsIgnoreCase(boardMemoryType))
                .toList();
        // RAM끼리 규격이 섞여도(DDR4+DDR5) 한 보드에 함께 장착할 수 없다 — 보드가 없어도 검출한다.
        boolean memoryMixed = ramMemoryTypes.size() > 1;
        boolean memoryMismatched = !mismatchedRamTypes.isEmpty();
        boolean memoryMatched = !memoryMixed && !memoryMismatched;
        boolean memoryTypeChecked = memoryMixed
                || (ramPresent && !ramTypeMissing && motherboard != null && boardMemoryType != null);
        // RAM·보드가 둘 다 담겼는데 어느 쪽이든 규격 정보가 없으면 '검사 못 함'을 명시한다(size의 결측 관례).
        boolean memoryTypeMissing = ramPresent && motherboard != null && (ramTypeMissing || boardMemoryType == null);
        boolean coolerMatched = socketSupported(cooler, stringAttr(cpu, "socket"));
        // 쿨러 냉각 용량(TDP 대응) — 소켓이 맞아도 65W급 쿨러에 170W CPU면 조립은 돼도 냉각이 안 된다.
        // 양쪽 tdpW가 모두 있을 때만 검사한다(ramSlotsChecked 관례) — 없는 데이터로 FAIL을 내지 않는다.
        int cpuTdpW = intAttr(cpu, "tdpW", 0);
        int coolerTdpW = intAttr(cooler, "tdpW", 0);
        boolean coolerTdpChecked = cpu != null && cooler != null && cpuTdpW > 0 && coolerTdpW > 0;
        boolean coolerTdpMatched = !coolerTdpChecked || coolerTdpW >= cpuTdpW;
        boolean coolerTdpMarginLow = coolerTdpChecked && coolerTdpMatched && coolerTdpW < Math.round(cpuTdpW * 1.2f);
        // RAM 폼팩터 — 노트북용 SODIMM·서버용 Registered(RDIMM)는 데스크탑 보드에 물리적으로 장착/부팅이 안 된다.
        // byCategory는 카테고리당 1개로 접히므로 RAM 전체 행을 순회한다. 속성이 없는 행은 검사에서 제외한다.
        List<String> ramBadFormFactors = new ArrayList<>();
        boolean ramFormFactorChecked = false;
        for (ToolBuildPart part : parts) {
            if (!"RAM".equalsIgnoreCase(firstText(part.category(), ""))) {
                continue;
            }
            String formFactor = stringAttr(part, "formFactor");
            boolean registered = boolAttr(part, "registered");
            if (formFactor != null || part.attributes().containsKey("registered")) {
                ramFormFactorChecked = true;
            }
            if ((formFactor != null && !"UDIMM".equalsIgnoreCase(formFactor)) || registered) {
                ramBadFormFactors.add(firstText(formFactor, "REGISTERED"));
            }
        }
        boolean ramFormFactorMatched = ramBadFormFactors.isEmpty();
        // 총 스틱 수 = Σ(수량 × 상품당 모듈 수). "32GB(16Gx2)" 킷은 moduleCount=2로 스틱 2개다.
        // byCategory는 카테고리당 1개로 접히므로 RAM 여러 상품이 담긴 견적은 전체 목록으로 합산한다.
        int ramSticksTotal = parts.stream()
                .filter(part -> "RAM".equalsIgnoreCase(firstText(part.category(), "")))
                .mapToInt(part -> part.effectiveQuantity() * Math.max(1, intAttr(part, "moduleCount", 1)))
                .sum();
        int memorySlots = intAttr(motherboard, "memorySlots", 0);
        // 보드에 memorySlots 데이터가 없으면(신규 인테이크 유입 등) 검사를 생략한다 — 없는 데이터로 FAIL을 내지 않는다.
        boolean ramSlotsChecked = motherboard != null && memorySlots > 0 && ramSticksTotal > 0;
        boolean ramSlotsMatched = !ramSlotsChecked || ramSticksTotal <= memorySlots;
        // M.2 SSD 장착 수 vs 보드 M.2 슬롯(P1-2). SATA(2.5인치) SSD는 M.2 슬롯을 쓰지 않으므로 제외한다 —
        // 인터페이스/폼팩터가 M.2·NVMe인 저장장치만 수량 가중으로 합산한다.
        int m2StorageTotal = parts.stream()
                .filter(part -> "STORAGE".equalsIgnoreCase(firstText(part.category(), "")))
                .filter(ToolCheckService::isM2Storage)
                .mapToInt(ToolBuildPart::effectiveQuantity)
                .sum();
        int m2Slots = intAttr(motherboard, "m2Slots", 0);
        // 보드에 m2Slots 데이터가 없으면(웹 검증 백필 전 유입 등) 검사를 생략한다.
        boolean m2SlotsChecked = motherboard != null && m2Slots > 0 && m2StorageTotal > 0;
        boolean m2SlotsMatched = !m2SlotsChecked || m2StorageTotal <= m2Slots;
        boolean pass = socketMatched && memoryMatched && coolerMatched && coolerTdpMatched
                && ramFormFactorMatched && ramSlotsMatched && m2SlotsMatched;
        // 실제 걸린 부품쌍 — 인사이트/보드가 문제와 무관한 부품(예: 소켓 문제인데 RAM)까지 칠하지 않게 details로 내린다.
        LinkedHashSet<String> issueCategories = new LinkedHashSet<>();
        if (!socketMatched) {
            issueCategories.add("CPU");
            issueCategories.add("MOTHERBOARD");
        }
        if (!memoryMatched || !ramFormFactorMatched || !ramSlotsMatched || memoryTypeMissing) {
            issueCategories.add("RAM");
            issueCategories.add("MOTHERBOARD");
        }
        if (!coolerMatched || !coolerTdpMatched) {
            issueCategories.add("COOLER");
            issueCategories.add("CPU");
        }
        if (!m2SlotsMatched) {
            issueCategories.add("STORAGE");
            issueCategories.add("MOTHERBOARD");
        }
        // 걸린 조건별 사유(categories+message) — 그래프가 사유를 부품쌍별 1:1로 귀속할 수 있게 details.issues로 내린다.
        // 우선순위: 물리 장착 불가(쿨러 소켓)가 냉각 용량(TDP)보다 먼저다 — 그래프 엣지와 우선순위를 통일한다.
        List<Map<String, Object>> issues = new ArrayList<>();
        List<String> failReasons = new ArrayList<>();
        if (!coolerMatched) {
            String message = "쿨러가 CPU 소켓(" + stringAttr(cpu, "socket") + ")을 지원하지 않습니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "COOLER", "CPU"));
        }
        if (!coolerTdpMatched) {
            String message = "쿨러 TDP " + coolerTdpW + "W가 CPU TDP " + cpuTdpW + "W에 못 미쳐 냉각이 부족합니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "COOLER", "CPU"));
        }
        if (!ramFormFactorMatched) {
            String message = ramBadFormFactors.stream()
                    .map(ToolCheckService::ramFormFactorLabel)
                    .distinct()
                    .collect(Collectors.joining(", "))
                    + " RAM이라 데스크탑 보드에 장착할 수 없습니다";
            failReasons.add(message);
            // RAM 폼팩터는 RAM 속성만 보는 검사라 사유 귀속도 RAM에만 건다(합집합 issueCategories는 하위호환 유지).
            issues.add(issue("FAIL", message, "RAM"));
        }
        if (!ramSlotsMatched) {
            String message = "RAM 스틱 수(" + ramSticksTotal + "개)가 메인보드 슬롯(" + memorySlots + "개)을 초과합니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "RAM", "MOTHERBOARD"));
        }
        if (!m2SlotsMatched) {
            String message = "M.2 SSD 수(" + m2StorageTotal + "개)가 메인보드 M.2 슬롯(" + m2Slots + "개)을 초과합니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "STORAGE", "MOTHERBOARD"));
        }
        if (!socketMatched) {
            String message = "CPU 소켓(" + stringAttr(cpu, "socket") + ")과 메인보드 소켓(" + stringAttr(motherboard, "socket") + ")이 달라 장착할 수 없습니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "CPU", "MOTHERBOARD"));
        }
        if (memoryMismatched) {
            String message = "RAM 규격(" + String.join(", ", mismatchedRamTypes) + ")과 메인보드 지원 메모리(" + boardMemoryType + ")가 다릅니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "RAM", "MOTHERBOARD"));
        } else if (memoryMixed) {
            String message = "서로 다른 RAM 규격(" + String.join(", ", ramMemoryTypes) + ")이 함께 담겨 있어 같은 보드에 장착할 수 없습니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "RAM"));
        }
        List<String> warnReasons = new ArrayList<>();
        if (memoryTypeMissing) {
            String message = "메모리 규격 정보가 없어 검사를 못 했습니다";
            warnReasons.add(message);
            issues.add(issue("WARN", message, "RAM", "MOTHERBOARD"));
        }
        String summary = !pass
                ? failReasons.get(0)
                : !warnReasons.isEmpty()
                        ? String.join(" · ", warnReasons)
                        : coolerTdpMarginLow
                                ? "쿨러 TDP 여유가 20% 미만이라 고부하 시 냉각 여유가 빠듯합니다."
                                : "CPU, 메인보드, RAM, 쿨러 기본 호환성이 맞습니다.";
        // status는 PASS/FAIL 2-상태가 기본이다 — TDP 마진 WARN을 툴 status로 올리면 compatibility를
        // 구독하는 RAM/메인보드 후보 전체가 후보와 무관한 쿨러 마진으로 '간섭 주의'가 된다.
        // 마진 경고는 details(coolerTdpMarginLow)와 summary로 내리고, CPU-쿨러 엣지가 WARN을 그린다.
        // 예외: 결측으로 검사를 생략한 경우만 WARN이다(size의 결측 관례와 동일) — 근거 없는 초록을 막는다.
        return tool("compatibility",
                !pass ? "FAIL" : !warnReasons.isEmpty() ? "WARN" : "PASS",
                socketMatched && memoryMatched ? "HIGH" : "MEDIUM",
                summary,
                MockData.map(
                        "socketMatched", socketMatched,
                        "memoryTypeMatched", memoryMatched,
                        "memoryTypeChecked", memoryTypeChecked,
                        "ramMemoryTypes", ramMemoryTypes.isEmpty() ? null : List.copyOf(ramMemoryTypes),
                        "coolerSocketMatched", coolerMatched,
                        "cpuTdpW", cpuTdpW > 0 ? cpuTdpW : null,
                        "coolerTdpW", coolerTdpW > 0 ? coolerTdpW : null,
                        "coolerTdpChecked", coolerTdpChecked,
                        "coolerTdpMatched", coolerTdpMatched,
                        "coolerTdpMarginLow", coolerTdpMarginLow,
                        "coolerTdpHeadroomW", coolerTdpChecked ? coolerTdpW - cpuTdpW : null,
                        "ramFormFactorChecked", ramFormFactorChecked,
                        "ramFormFactorMatched", ramFormFactorMatched,
                        "ramBadFormFactors", ramBadFormFactors.isEmpty() ? null : ramBadFormFactors,
                        "ramSticksTotal", ramSticksTotal,
                        "memorySlots", memorySlots > 0 ? memorySlots : null,
                        "ramSlotsChecked", ramSlotsChecked,
                        "ramSlotsMatched", ramSlotsMatched,
                        "m2StorageTotal", m2StorageTotal,
                        "m2Slots", m2Slots > 0 ? m2Slots : null,
                        "m2SlotsChecked", m2SlotsChecked,
                        "m2SlotsMatched", m2SlotsMatched,
                        // 문제에 실제로 연루된 부품쌍 — 그래프 인사이트가 무관 부품을 칠하지 않게 쓴다.
                        "issueCategories", issueCategories.isEmpty() ? null : List.copyOf(issueCategories),
                        // 걸린 조건별 사유 — categories(PART 카테고리 대문자 배열)와 완성 문장 message의 쌍.
                        "issues", issues.isEmpty() ? null : List.copyOf(issues)));
    }

    /** RAM 폼팩터 원어(SODIMM/RDIMM/REGISTERED)를 사용자 언어 라벨로 바꾼다. 모르는 표기는 원문 유지. */
    private static String ramFormFactorLabel(String token) {
        String normalized = firstText(token, "").toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (normalized.contains("SODIMM")) {
            return "노트북용(SO-DIMM)";
        }
        if (normalized.contains("RDIMM") || normalized.contains("REGISTERED")) {
            return "레지스터드(서버용)";
        }
        return firstText(token, "규격 미상");
    }

    /** details.issues 항목 — 걸린 조건 1건의 상태·연루 부품 카테고리·완성 문장 사유. */
    private static Map<String, Object> issue(String status, String message, String... categories) {
        return MockData.map("categories", List.of(categories), "message", message, "status", status);
    }

    /** 저장장치가 M.2 슬롯을 차지하는지 — 인터페이스/폼팩터가 M.2·NVMe면 M.2, SATA 2.5인치는 아니다. */
    private static boolean isM2Storage(ToolBuildPart part) {
        String iface = firstText(stringAttr(part, "interface"), "").toUpperCase(Locale.ROOT);
        String formFactor = firstText(stringAttr(part, "formFactor"), "").toUpperCase(Locale.ROOT);
        if (formFactor.contains("M.2") || formFactor.contains("M2")) {
            return true;
        }
        if (formFactor.contains("2.5") || iface.equals("SATA")) {
            return false;
        }
        // 인터페이스만 있는 경우: NVMe/M.2/PCIe는 M.2로 본다(현재 카탈로그 M.2 NVMe 표기 기준).
        return iface.contains("M.2") || iface.contains("NVME") || iface.contains("PCIE");
    }

    /** Evaluates PSU rated capacity against estimated build load. */
    private Map<String, Object> power(Map<String, ToolBuildPart> byCategory, List<ToolBuildPart> parts) {
        ToolBuildPart gpu = byCategory.get("GPU");
        ToolBuildPart psu = byCategory.get("PSU");
        // byCategory는 카테고리당 1개로 접힌다 — SSD 3개·RAM 2종 같은 복수 장착의 전력이 빠지지 않게
        // 전체 목록으로 합산한다(수량 가중은 estimatedWattage 내부).
        int estimatedWattage = estimatedWattage(parts);
        int psuCapacity = intAttr(psu, "capacityW", 0);
        int vendorRecommendedPsu = intAttr(gpu, "requiredSystemPowerW", 0);
        int requiredRatedCapacity = Math.max(vendorRecommendedPsu, estimatedWattage + 120);
        // 소비전력 데이터가 없어 부하 합산에서 0으로 빠진 부품(현재 기본값 0은 GPU 계열뿐) —
        // 숫자를 지어내지 않는 대신 신뢰도를 낮추고 문구에 명시한다. 판정 자체는 뒤집지 않는다.
        List<String> wattageUnknownCategories = parts.stream()
                .filter(part -> part != null && !"PSU".equals(part.category()))
                .filter(part -> estimatedPartPowerDraw(part) <= 0)
                .map(ToolBuildPart::category)
                .distinct()
                .toList();
        String note = "capacityW는 정격 출력이며 PSU 자체 소비전력이나 피크 부하로 합산하지 않습니다.";
        if (psu == null || psuCapacity <= 0) {
            // 결측을 0W로 계산해 '현재 정격 0W' 같은 허구 숫자 확정문을 내지 않는다(size의 결측=null 관례).
            // PSU 미장착 FAIL은 레거시 직접 호출 경로용이다 — 보드 경로는 ToolApplicabilityPolicy가 결과를 거른다.
            Map<String, Object> details = MockData.map(
                    "estimatedContinuousLoadW", estimatedWattage,
                    "psuRatedCapacityW", null,
                    "vendorRecommendedPsuW", vendorRecommendedPsu,
                    "requiredRatedCapacityW", requiredRatedCapacity,
                    "ratedHeadroomW", null,
                    "ratedLoadPercent", null,
                    "wattageUnknownCategories", wattageUnknownCategories.isEmpty() ? null : wattageUnknownCategories,
                    "note", note);
            if (psu == null) {
                return tool("power", "FAIL", "MEDIUM",
                        "파워(PSU)가 없어 시스템 전력을 공급할 수 없습니다", details);
            }
            return tool("power", "WARN", "MEDIUM",
                    "파워 용량 정보가 없어 전력 검사를 못 했습니다", details);
        }
        int headroom = psuCapacity - estimatedWattage;
        int loadPercent = (int) Math.round((estimatedWattage * 100.0) / psuCapacity);
        // 그래프 GPU 노드에 "권장 파워"로 표시되는 값(requiredSystemPowerW)을 담은 PSU가 충족하면,
        // 내부 추정 기준(estimatedWattage+120)에 못 미쳐도 빨간 FAIL이 아니라 WARN으로 둔다.
        // 화면엔 "권장 750W"라 해놓고 750W PSU를 담았을 때 FAIL이 뜨는 모순을 막기 위함이다.
        boolean meetsVendorRecommendation = vendorRecommendedPsu > 0 && psuCapacity >= vendorRecommendedPsu;
        boolean pass = psuCapacity >= requiredRatedCapacity && loadPercent <= 85;
        boolean warn = psuCapacity >= estimatedWattage && (headroom >= 80 || meetsVendorRecommendation);
        // 문구는 실제 걸린 조건만 말한다 — 벤더 권장 미달이 원인인데 "여유 210W로 빠듯" 같은 모순을 내지 않는다.
        boolean vendorShortfall = vendorRecommendedPsu > 0 && psuCapacity < vendorRecommendedPsu;
        boolean estimateShortfall = psuCapacity < estimatedWattage + 120 || loadPercent > 85;
        String summary;
        if (pass) {
            summary = "PSU 정격 출력이 예상 지속 부하와 GPU 권장 정격 파워를 충족합니다.";
        } else if (warn) {
            List<String> reasons = new ArrayList<>();
            if (vendorShortfall) {
                reasons.add("GPU 권장 파워 " + vendorRecommendedPsu + "W에 못 미칩니다(현재 정격 " + psuCapacity + "W)");
            }
            if (estimateShortfall) {
                reasons.add("PSU 정격 " + psuCapacity + "W가 예상 부하 " + estimatedWattage + "W 대비 여유 " + headroom + "W로 빠듯합니다");
            }
            summary = String.join(" · ", reasons);
        } else {
            List<String> reasons = new ArrayList<>();
            if (headroom < 0) {
                reasons.add("예상 부하 " + estimatedWattage + "W가 PSU 정격 " + psuCapacity + "W를 초과합니다(부족 " + (-headroom) + "W)");
            } else {
                reasons.add("PSU 정격 " + psuCapacity + "W가 예상 부하 " + estimatedWattage + "W 대비 여유 " + headroom + "W뿐이라 부족합니다");
            }
            if (vendorShortfall) {
                reasons.add("GPU 권장 파워 " + vendorRecommendedPsu + "W에 못 미칩니다(현재 정격 " + psuCapacity + "W)");
            }
            summary = String.join(" · ", reasons);
        }
        if (!wattageUnknownCategories.isEmpty()) {
            String base = summary.endsWith(".") ? summary.substring(0, summary.length() - 1) : summary;
            summary = base + " · 일부 부품의 소비전력 정보가 없어 실제 부하는 더 높을 수 있습니다";
        }
        String confidence = !wattageUnknownCategories.isEmpty() ? "LOW"
                : headroom >= 180 && loadPercent <= 80 ? "HIGH" : "MEDIUM";
        return tool("power",
                pass ? "PASS" : warn ? "WARN" : "FAIL",
                confidence,
                summary,
                MockData.map(
                        "estimatedContinuousLoadW", estimatedWattage,
                        "psuRatedCapacityW", psuCapacity,
                        "vendorRecommendedPsuW", vendorRecommendedPsu,
                        "requiredRatedCapacityW", requiredRatedCapacity,
                        "ratedHeadroomW", headroom,
                        "ratedLoadPercent", loadPercent,
                        "wattageUnknownCategories", wattageUnknownCategories.isEmpty() ? null : wattageUnknownCategories,
                        "note", note
                ));
    }

    /** Evaluates GPU length, CPU cooler height or AIO radiator fit, PSU depth, and board form factor against case limits. */
    private Map<String, Object> size(Map<String, ToolBuildPart> byCategory) {
        ToolBuildPart gpu = byCategory.get("GPU");
        ToolBuildPart pcCase = byCategory.get("CASE");
        ToolBuildPart cooler = byCategory.get("COOLER");
        ToolBuildPart psu = byCategory.get("PSU");
        ToolBuildPart motherboard = byCategory.get("MOTHERBOARD");
        // 소수 치수는 보수적 방향으로 정수화한다 — 부품 치수는 올림, 케이스 허용치는 내림.
        // (357.6mm를 357로 내림하면 여유가 실제보다 커 보인다.)
        int gpuLength = partDimensionMm(gpu, "lengthMm");
        int maxGpuLength = caseLimitMm(pcCase, "maxGpuLengthMm");
        int coolerHeight = partDimensionMm(cooler, "heightMm", "coolerHeightMm");
        // 케이스에 허용 높이 데이터가 없으면 검사를 생략한다 — 임의 기본값(과거 190)으로 '근거 있는 통과'처럼 보이게 하지 않는다.
        int maxCoolerHeight = caseLimitMm(pcCase, "maxCpuCoolerHeightMm");
        // 수랭(AIO)은 heightMm가 라디에이터 두께(27~38mm)라 공랭용 높이 검사가 무의미하다.
        // 대신 라디에이터 크기가 케이스 지원 목록(radiatorSupportMm 배열)에 포함되는지를 본다.
        String coolerType = stringAttr(cooler, "coolerType");
        boolean aioCooler = isLiquidCoolerType(coolerType);
        int radiatorSizeMm = partDimensionMm(cooler, "radiatorSizeMm");
        List<Integer> radiatorSupportMm = intListAttr(pcCase, "radiatorSupportMm");
        boolean radiatorChecked = aioCooler && radiatorSizeMm > 0 && pcCase != null;
        boolean radiatorSupportKnown = !radiatorSupportMm.isEmpty();
        boolean radiatorMatched = !radiatorChecked || !radiatorSupportKnown || radiatorSupportMm.contains(radiatorSizeMm);
        int psuDepth = partDimensionMm(psu, "depthMm");
        int maxPsuLength = caseLimitMm(pcCase, "maxPsuLengthMm");
        // 보드 폼팩터 vs 케이스 지원 규격(P1-1) — 케이스 값은 'EATX_ATX_MATX_ITX' 같은 지원 목록 문자열이라
        // 토큰 최대 랭크로 해석한다(작은 보드는 큰 케이스에 항상 장착 가능한 표준 홀 규격 위계).
        String boardFormFactor = stringAttr(motherboard, "formFactor");
        int boardFormFactorRank = formFactorRank(boardFormFactor);
        int caseMaxFormFactorRank = caseMaxFormFactorRank(stringAttr(pcCase, "formFactor"));
        boolean boardFormFactorChecked = motherboard != null && pcCase != null
                && boardFormFactorRank >= 0 && caseMaxFormFactorRank >= 0;
        boolean boardFormFactorMatched = !boardFormFactorChecked || boardFormFactorRank <= caseMaxFormFactorRank;
        boolean gpuKnown = gpuLength > 0 && maxGpuLength > 0;
        boolean coolerKnown = !aioCooler && coolerHeight > 0 && maxCoolerHeight > 0;
        boolean psuKnown = psuDepth > 0 && maxPsuLength > 0;
        boolean gpuExceeded = gpuKnown && gpuLength > maxGpuLength;
        boolean coolerExceeded = coolerKnown && coolerHeight > maxCoolerHeight;
        boolean psuExceeded = psuKnown && psuDepth > maxPsuLength;
        boolean radiatorExceeded = radiatorChecked && radiatorSupportKnown && !radiatorMatched;
        boolean boardFormFactorExceeded = boardFormFactorChecked && !boardFormFactorMatched;
        boolean fail = gpuExceeded || coolerExceeded || psuExceeded || radiatorExceeded || boardFormFactorExceeded;
        int gpuHeadroom = gpuKnown ? maxGpuLength - gpuLength : 0;
        int coolerHeadroom = coolerKnown ? maxCoolerHeight - coolerHeight : 0;
        int psuHeadroom = psuKnown ? maxPsuLength - psuDepth : 0;
        // '근거 부족' WARN은 해당 부품 쌍이 실제로 담겨 있을 때만 낸다 — GPU나 케이스를 아직 안 담은
        // 견적이 WARN이 되면, size를 구독하는 쿨러/파워 후보 패널 전체가 무관한 사유로 '간섭 주의'가 된다.
        // 사유는 걸린 조건을 그대로 단정문으로 말한다 — "~하거나 ~해서 추가 확인 필요" 같은 뭉뚱그림은
        // 보드 팝오버(왜 문제인지)의 목적과 어긋난다(부연 설명은 'AI에게 설명' 몫).
        List<String> failReasons = new ArrayList<>();
        List<String> warnReasons = new ArrayList<>();
        // 걸린 조건별 사유(categories+message) — 그래프가 사유를 부품쌍별 1:1로 귀속할 수 있게 details.issues로 내린다.
        List<Map<String, Object>> issues = new ArrayList<>();
        // 실제 걸린 부품쌍 — 인사이트/보드가 문제와 무관한 부품까지 노랗게 칠하지 않도록 details로 내린다.
        LinkedHashSet<String> issueCategories = new LinkedHashSet<>();
        if (gpuExceeded) {
            String message = "GPU 길이(" + gpuLength + "mm)가 케이스 허용(" + maxGpuLength + "mm)을 초과합니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "GPU", "CASE"));
            issueCategories.add("GPU");
            issueCategories.add("CASE");
        }
        if (coolerExceeded) {
            String message = "쿨러 높이(" + coolerHeight + "mm)가 케이스 허용(" + maxCoolerHeight + "mm)을 초과합니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "COOLER", "CASE"));
            issueCategories.add("COOLER");
            issueCategories.add("CASE");
        }
        if (radiatorExceeded) {
            String message = "케이스가 라디에이터 " + radiatorSizeMm + "mm 장착을 지원하지 않습니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "COOLER", "CASE"));
            issueCategories.add("COOLER");
            issueCategories.add("CASE");
        }
        if (psuExceeded) {
            String message = "파워 깊이(" + psuDepth + "mm)가 케이스 허용(" + maxPsuLength + "mm)을 초과합니다";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "PSU", "CASE"));
            issueCategories.add("PSU");
            issueCategories.add("CASE");
        }
        if (boardFormFactorExceeded) {
            String message = "케이스가 " + formFactorLabel(boardFormFactorRank) + " 규격 메인보드 장착을 지원하지 않습니다(최대 " + formFactorLabel(caseMaxFormFactorRank) + ")";
            failReasons.add(message);
            issues.add(issue("FAIL", message, "MOTHERBOARD", "CASE"));
            issueCategories.add("MOTHERBOARD");
            issueCategories.add("CASE");
        }
        if (gpu != null && pcCase != null && !gpuKnown) {
            String message = gpuLength <= 0
                    ? "GPU 길이 정보가 없어 케이스 장착 검사를 못 했습니다"
                    : "케이스의 GPU 허용 길이 정보가 없어 장착 검사를 못 했습니다";
            warnReasons.add(message);
            issues.add(issue("WARN", message, "GPU", "CASE"));
            issueCategories.add("GPU");
            issueCategories.add("CASE");
        }
        if (cooler != null && pcCase != null && !aioCooler && !coolerKnown) {
            String message = coolerHeight <= 0
                    ? "쿨러 높이 정보가 없어 케이스 장착 검사를 못 했습니다"
                    : "케이스의 쿨러 허용 높이 정보가 없어 장착 검사를 못 했습니다";
            warnReasons.add(message);
            issues.add(issue("WARN", message, "COOLER", "CASE"));
            issueCategories.add("COOLER");
            issueCategories.add("CASE");
        }
        if (aioCooler && pcCase != null && (!radiatorChecked || !radiatorSupportKnown)) {
            String message = !radiatorChecked
                    ? "수랭 라디에이터 크기 정보가 없어 케이스 장착 검사를 못 했습니다"
                    : "케이스의 라디에이터 지원 정보가 없어 장착 검사를 못 했습니다";
            warnReasons.add(message);
            issues.add(issue("WARN", message, "COOLER", "CASE"));
            issueCategories.add("COOLER");
            issueCategories.add("CASE");
        }
        if (psu != null && pcCase != null && !psuKnown) {
            String message = psuDepth <= 0
                    ? "파워 깊이 정보가 없어 케이스 장착 검사를 못 했습니다"
                    : "케이스의 파워 허용 깊이 정보가 없어 장착 검사를 못 했습니다";
            warnReasons.add(message);
            issues.add(issue("WARN", message, "PSU", "CASE"));
            issueCategories.add("PSU");
            issueCategories.add("CASE");
        }
        if (motherboard != null && pcCase != null && !boardFormFactorChecked) {
            // 값이 실재하는데 해석만 못 하는 경우(SSI-EEB 등)는 '정보가 없다'고 말하지 않는다 — 원문을 인용한다.
            String message;
            if (boardFormFactorRank < 0) {
                message = boardFormFactor == null
                        ? "메인보드 규격 정보가 없어 케이스 장착 검사를 못 했습니다"
                        : "지원 여부를 판정할 수 없는 메인보드 규격(" + boardFormFactor + ")이라 케이스 장착 검사를 못 했습니다";
            } else {
                String caseFormFactor = stringAttr(pcCase, "formFactor");
                message = caseFormFactor == null
                        ? "케이스의 지원 보드 규격 정보가 없어 장착 검사를 못 했습니다"
                        : "지원 여부를 판정할 수 없는 케이스 규격(" + caseFormFactor + ")이라 장착 검사를 못 했습니다";
            }
            warnReasons.add(message);
            issues.add(issue("WARN", message, "MOTHERBOARD", "CASE"));
            issueCategories.add("MOTHERBOARD");
            issueCategories.add("CASE");
        }
        if (gpuKnown && BuildSizeFitPolicy.hasLowHeadroom(gpuHeadroom, BuildSizeFitPolicy.GPU_WARN_HEADROOM_MM)) {
            String message = "케이스의 GPU 길이 여유가 " + gpuHeadroom + "mm뿐입니다";
            warnReasons.add(message);
            issues.add(issue("WARN", message, "GPU", "CASE"));
            issueCategories.add("GPU");
            issueCategories.add("CASE");
        }
        // known 게이트 필수 — 수랭(AIO)은 coolerKnown=false라 headroom이 0으로 남는데,
        // 게이트 없이 0<5를 평가하면 라디에이터가 정확히 맞아도 영구 WARN이 된다.
        if (coolerKnown && BuildSizeFitPolicy.hasLowHeadroom(coolerHeadroom, BuildSizeFitPolicy.COOLER_WARN_HEADROOM_MM)) {
            String message = "케이스의 쿨러 높이 여유가 " + coolerHeadroom + "mm뿐입니다";
            warnReasons.add(message);
            issues.add(issue("WARN", message, "COOLER", "CASE"));
            issueCategories.add("COOLER");
            issueCategories.add("CASE");
        }
        if (psuKnown && BuildSizeFitPolicy.hasLowHeadroom(psuHeadroom, BuildSizeFitPolicy.PSU_WARN_HEADROOM_MM)) {
            String message = "케이스의 파워 깊이 여유가 " + psuHeadroom + "mm뿐입니다";
            warnReasons.add(message);
            issues.add(issue("WARN", message, "PSU", "CASE"));
            issueCategories.add("PSU");
            issueCategories.add("CASE");
        }
        boolean warn = !fail && !warnReasons.isEmpty();
        // PASS 요약은 실제 검사한 항목만 말한다 — 검사 0건인데 전부 통과한 것처럼 단정하지 않는다.
        List<String> checkedLabels = new ArrayList<>();
        if (gpuKnown) {
            checkedLabels.add("GPU 길이");
        }
        if (coolerKnown) {
            checkedLabels.add("쿨러 높이");
        }
        if (radiatorChecked && radiatorSupportKnown) {
            checkedLabels.add("라디에이터 크기");
        }
        if (psuKnown) {
            checkedLabels.add("파워 깊이");
        }
        if (boardFormFactorChecked) {
            checkedLabels.add("보드 규격");
        }
        String passSummary = checkedLabels.isEmpty()
                ? "케이스 장착 검사를 수행할 부품 조합이 아직 없습니다."
                : String.join(", ", checkedLabels)
                        + subjectParticle(checkedLabels.get(checkedLabels.size() - 1))
                        + " 케이스 제약 안에 있습니다.";
        return tool("size",
                fail ? "FAIL" : warn ? "WARN" : "PASS",
                fail ? "HIGH" : "MEDIUM",
                // FAIL도 WARN처럼 걸린 조건 전부를 수치 문구로 join한다 — 정보 없는 일반문을 내지 않는다.
                fail ? String.join(" · ", failReasons)
                        : warn ? String.join(" · ", warnReasons)
                        : passSummary,
                // 결측(0)은 null로 내린다 — 0을 그대로 실으면 엣지가 'max-0' 여유로 초록을 그려
                // "근거 없는 통과"처럼 보인다(190 기본값 제거와 같은 원칙).
                MockData.map(
                        "gpuLengthMm", gpuLength > 0 ? gpuLength : null,
                        "maxGpuLengthMm", maxGpuLength > 0 ? maxGpuLength : null,
                        "gpuHeadroomMm", gpuKnown ? gpuHeadroom : null,
                        "coolerHeightMm", !aioCooler && coolerHeight > 0 ? coolerHeight : null,
                        "maxCpuCoolerHeightMm", maxCoolerHeight > 0 ? maxCoolerHeight : null,
                        "coolerHeadroomMm", coolerKnown ? coolerHeadroom : null,
                        "coolerType", coolerType,
                        "radiatorSizeMm", radiatorSizeMm > 0 ? radiatorSizeMm : null,
                        "radiatorSupportMm", radiatorSupportKnown ? radiatorSupportMm : null,
                        "radiatorChecked", radiatorChecked,
                        "radiatorMatched", radiatorMatched,
                        "psuDepthMm", psuDepth > 0 ? psuDepth : null,
                        "maxPsuLengthMm", maxPsuLength > 0 ? maxPsuLength : null,
                        "psuHeadroomMm", psuKnown ? psuHeadroom : null,
                        "boardFormFactor", boardFormFactorRank >= 0 ? formFactorLabel(boardFormFactorRank) : null,
                        "caseMaxFormFactor", caseMaxFormFactorRank >= 0 ? formFactorLabel(caseMaxFormFactorRank) : null,
                        "boardFormFactorChecked", boardFormFactorChecked,
                        "boardFormFactorMatched", boardFormFactorMatched,
                        // 문제에 실제로 연루된 부품쌍 — 그래프 인사이트가 무관 부품을 칠하지 않게 쓴다.
                        "issueCategories", issueCategories.isEmpty() ? null : List.copyOf(issueCategories),
                        // 걸린 조건별 사유 — categories(PART 카테고리 대문자 배열)와 완성 문장 message의 쌍.
                        "issues", issues.isEmpty() ? null : List.copyOf(issues)
                ));
    }

    /** 수랭(AIO) 판별 — LIQUID 외에 AIO/WATER/수랭 표기도 수랭으로 본다(대소문자 무시). */
    private static boolean isLiquidCoolerType(String coolerType) {
        if (coolerType == null) {
            return false;
        }
        String normalized = coolerType.toUpperCase(Locale.ROOT);
        return normalized.contains("LIQUID") || normalized.contains("AIO")
                || normalized.contains("WATER") || normalized.contains("수랭");
    }

    /** 한국어 주격 조사(이/가) — 받침 유무로 고른다(PASS 요약 동적 조립용). */
    private static String subjectParticle(String word) {
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "이(가)";
        }
        return (last - 0xAC00) % 28 != 0 ? "이" : "가";
    }

    /**
     * 폼팩터 문자열 → 크기 랭크. 표기 변형('M-ATX'/'Micro-ATX'/'MATX', 'Mini-ITX'/'MINI_ITX'/'ITX')을
     * 흡수하고, 모르는 값은 -1(검사 생략)로 처리해 근거 없는 판정을 막는다.
     */
    private static int formFactorRank(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String normalized = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return switch (normalized) {
            case "ITX", "MINIITX" -> 0;
            case "MATX", "MICROATX" -> 1;
            case "ATX" -> 2;
            case "EATX", "EXTENDEDATX" -> 3;
            default -> -1;
        };
    }

    /** 케이스 지원 문자열('EATX_ATX_MATX_ITX' 등)을 토큰으로 쪼개 지원 가능한 최대 랭크를 구한다. */
    private static int caseMaxFormFactorRank(String support) {
        if (support == null || support.isBlank()) {
            return -1;
        }
        int max = -1;
        for (String token : support.split("[_/,\\s]+")) {
            max = Math.max(max, formFactorRank(token));
        }
        return max;
    }

    private static String formFactorLabel(int rank) {
        return switch (rank) {
            case 0 -> "Mini-ITX";
            case 1 -> "M-ATX";
            case 2 -> "ATX";
            case 3 -> "E-ATX";
            default -> "알 수 없음";
        };
    }

    /** Evaluates coarse workload fit without promising exact FPS. */
    private Map<String, Object> performance(Map<String, ToolBuildPart> byCategory, Map<String, Object> context) {
        return performance(byCategory, context, null);
    }

    private Map<String, Object> performance(
            Map<String, ToolBuildPart> byCategory,
            Map<String, Object> context,
            Map<Long, Map<String, Object>> prefetchedBenchmarks
    ) {
        ToolBuildPart cpu = byCategory.get("CPU");
        ToolBuildPart gpu = byCategory.get("GPU");
        Map<Long, Map<String, Object>> benchmarkRows = prefetchedBenchmarks != null
                ? prefetchedBenchmarks
                : latestBenchmarks(new ArrayList<>(byCategory.values()));
        Double cpuScore = benchmarkScore(benchmarkRows, cpu);
        Double gpuScore = benchmarkScore(benchmarkRows, gpu);
        int vramGb = intAttr(gpu, "vramGb", 0);
        boolean benchmarkBacked = cpuScore != null || gpuScore != null;
        boolean pass = benchmarkBacked
                ? (gpuScore == null || gpuScore >= 70.0) && (cpuScore == null || cpuScore >= 60.0)
                : vramGb >= 12;
        List<Map<String, Object>> gameFpsEvidence = gameFpsEvidence(cpu, gpu, context);
        String gameFpsEvidenceStatus = gameFpsEvidenceStatus(context, gameFpsEvidence);
        Map<String, Object> details = MockData.map(
                "gpu", name(gpu),
                "vramGb", vramGb,
                "gpuBenchmarkScore", gpuScore,
                "gpuBenchmarkSummary", benchmarkSummary(benchmarkRows, gpu),
                "cpu", name(cpu),
                "cpuBenchmarkScore", cpuScore,
                "cpuBenchmarkSummary", benchmarkSummary(benchmarkRows, cpu),
                "usageTags", context.getOrDefault("usageTags", List.of()),
                "gameFpsEvidence", gameFpsEvidence,
                "benchmarkSource", benchmarkBacked ? "benchmark_summaries" : "attributes_fallback",
                "guaranteePolicy", "NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE"
        );
        if (!gameFpsEvidence.isEmpty()) {
            details.put("gameFpsEvidenceStatus", gameFpsEvidenceStatus);
        }
        return tool("performance",
                pass ? "PASS" : "WARN",
                benchmarkBacked ? "HIGH" : "MEDIUM",
                pass
                        ? "공개 벤치마크/공식 스펙 기반 적합도 점수상 요구 작업에 무리가 적은 조합입니다. 점수는 참고용이며 실제 성능을 보장하지 않습니다."
                        : "성능 또는 작업 적합도 여유가 낮아 상위 부품을 검토해야 합니다",
                details);
    }

    /** Evaluates saved current prices against the selected budget. */
    private Map<String, Object> price(List<ToolBuildPart> parts, int budget, int currentTotalPrice) {
        int total = currentTotalPrice > 0 ? currentTotalPrice : total(parts);
        int overBudget = total - budget;
        String status = total <= budget ? "PASS" : total <= Math.round(budget * 1.08) ? "WARN" : "FAIL";
        // 초과면 얼마나 초과인지 실값(예산·총액·차액)을 말한다 — WARN(8% 유예 이내)과 FAIL을 문구로 구분한다.
        String summary = switch (status) {
            case "PASS" -> "저장된 현재가 기준 예산 안에 들어옵니다.";
            case "WARN" -> "저장된 현재가 기준 총액 " + won(total) + "원이 예산 " + won(budget) + "원을 "
                    + won(overBudget) + "원 초과합니다(예산의 8% 이내)";
            default -> "저장된 현재가 기준 총액 " + won(total) + "원이 예산 " + won(budget) + "원을 "
                    + won(overBudget) + "원 초과합니다";
        };
        return tool("price", status, "HIGH", summary,
                MockData.map("budget", budget, "totalPrice", total, "priceDiff", total - budget));
    }

    /** 원화 금액 표기 — 천 단위 구분자. */
    private static String won(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /** Resolves buildId or partIds from a Tool API request. */
    private List<ToolBuildPart> resolveParts(Map<String, Object> request) {
        String buildId = text(request.get("buildId"));
        if (buildId != null) {
            return partsByBuildId(buildId);
        }
        List<String> partIds = stringList(request.get("partIds"));
        return partIds.isEmpty() ? List.of() : partsByPublicIds(partIds);
    }

    /** Resolves the concrete build parts that an Agent root can validate. */
    private AgentRootParts resolveAgentRootParts(String rootType, String rootId) {
        String normalizedType = firstText(text(rootType), "").toUpperCase(Locale.ROOT);
        String id = text(rootId);
        if (id == null) {
            return new AgentRootParts(List.of(), null);
        }
        return switch (normalizedType) {
            case "BUILD" -> new AgentRootParts(partsByBuildId(id), budgetByBuildId(id));
            case "REQUIREMENT" -> partsByRequirementId(id);
            default -> new AgentRootParts(List.of(), null);
        };
    }

    /** Loads build items as Tool-ready part DTOs. */
    private List<ToolBuildPart> partsByBuildId(String buildId) {
        // build_items에는 quantity 컬럼이 없다(DB_SCHEMA §build_items — 수량은 price에 line total로만 반영).
        // 그래서 이 경로는 1개분으로 계산된다(quantity null → 1). AI_BUILD·QUOTE_DRAFT 경로만 수량을 안다.
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               bi.price,
                               p.attributes
                        FROM build_items bi
                        JOIN builds b ON b.id = bi.build_id
                        JOIN parts p ON p.id = bi.part_id
                        WHERE b.public_id = ?::uuid
                        ORDER BY bi.id
                        """, buildId)
                .stream()
                .map(this::part)
                .toList();
    }

    /** Loads the newest build parts for a requirement-root Agent run. */
    private AgentRootParts partsByRequirementId(String requirementId) {
        List<String> buildIds = jdbcTemplate.queryForList("""
                SELECT b.public_id::text
                FROM builds b
                JOIN requirements r ON r.id = b.requirement_id
                WHERE r.public_id = ?::uuid
                ORDER BY b.created_at DESC, b.id DESC
                LIMIT 1
                """, String.class, requirementId);
        if (buildIds.isEmpty()) {
            return new AgentRootParts(List.of(), budgetByRequirementId(requirementId));
        }
        return new AgentRootParts(partsByBuildId(buildIds.get(0)), budgetByRequirementId(requirementId));
    }

    /** Reads the user budget connected to a build. */
    private Integer budgetByBuildId(String buildId) {
        List<Integer> rows = jdbcTemplate.queryForList("""
                SELECT r.budget
                FROM builds b
                JOIN requirements r ON r.id = b.requirement_id
                WHERE b.public_id = ?::uuid
                """, Integer.class, buildId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Reads the user budget connected to a requirement. */
    private Integer budgetByRequirementId(String requirementId) {
        List<Integer> rows = jdbcTemplate.queryForList("""
                SELECT budget
                FROM requirements
                WHERE public_id = ?::uuid
                """, Integer.class, requirementId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Loads explicit partIds as Tool-ready part DTOs. */
    private List<ToolBuildPart> partsByPublicIds(List<String> partIds) {
        return partQuery.partsByPublicIds(partIds);
    }

    /** Loads the latest category-local benchmark/fit score for selected parts. */
    private Map<Long, Map<String, Object>> latestBenchmarks(List<ToolBuildPart> parts) {
        List<Long> partIds = parts.stream()
                .filter(part -> part != null && part.internalId() != null)
                .map(ToolBuildPart::internalId)
                .distinct()
                .toList();
        return benchmarkQuery.latestBenchmarkInfos(partIds);
    }

    /** Loads game-specific public FPS evidence for selected CPU/GPU context. */
    private List<Map<String, Object>> gameFpsEvidence(ToolBuildPart cpu, ToolBuildPart gpu, Map<String, Object> context) {
        if (gpu == null || gpu.internalId() == null) {
            return List.of();
        }
        String gameKey = gameKey(context);
        String resolution = resolution(context);
        String gpuClass = hardwareClass(gpu);
        String cpuClass = hardwareClass(cpu);
        if (gameKey == null && !gamingContext(context)) {
            return List.of();
        }

        List<Object> params = new ArrayList<>();
        params.add(gpu.internalId());
        params.add(gpuClass);
        params.add(cpu == null ? -1L : cpu.internalId());
        params.add(cpu == null ? -1L : cpu.internalId());
        params.add(cpuClass);
        params.add(resolution);
        String gameFilter = "";
        if (gameKey != null) {
            gameFilter = " AND game_key = ?\n";
            params.add(gameKey);
        }

        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               game_title,
                               game_key,
                               resolution,
                               graphics_preset,
                               avg_fps,
                               one_percent_low_fps,
                               source_name,
                               source_url,
                               source_checked_at,
                               confidence,
                               metadata,
                               CASE
                                 WHEN gpu_part_id = ? THEN 0
                                 WHEN metadata->>'gpuClass' = ? THEN 1
                                 ELSE 2
                               END AS gpu_match_rank,
                               CASE
                                 WHEN cpu_part_id = ? THEN 0
                                 WHEN ? = -1 THEN 2
                                 WHEN metadata->>'cpuClass' = ? THEN 1
                                 ELSE 2
                               END AS cpu_match_rank,
                               CASE
                                 WHEN ? IS NOT NULL AND resolution = ? THEN 0
                                 WHEN ? IS NULL THEN 0
                                 ELSE 1
                               END AS resolution_rank
                        FROM game_fps_benchmarks
                        WHERE deleted_at IS NULL
                          AND (gpu_part_id = ? OR metadata->>'gpuClass' = ?)
                        """ + gameFilter + """
                        ORDER BY gpu_match_rank,
                                 cpu_match_rank,
                                 resolution_rank,
                                 CASE confidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                                 source_checked_at DESC,
                                 game_fps_benchmarks.id DESC
                        LIMIT 3
                        """, fpsParams(params, gpu.internalId(), gpuClass, gameKey).toArray())
                .stream()
                .map(row -> gameFpsEvidenceMap(row, gpuClass, cpuClass, gameKey, resolution))
                .toList();
    }

    /** Keeps legacy direct Tool calls useful when no concrete parts are supplied. */
    private Map<String, Object> seedBackedToolResult(String tool) {
        Map<String, Object> rule = ruleFor(tool);
        String category = categoryForTool(tool);
        String status = rule == null ? defaultStatus(tool) : DbValueMapper.string(rule, "status");
        String summary = rule == null ? "DB seed result for " + tool : DbValueMapper.string(rule, "summary");
        return tool(tool, status, "MEDIUM", summary, MockData.map(
                "checkedPartIds", toolReadyPartIds(category, 3),
                "candidateCategory", category,
                "source", "db-seed",
                "toolName", tool
        ));
    }

    /** Expands FPS query parameters while keeping optional game filter readable. */
    private static List<Object> fpsParams(List<Object> base, Long gpuId, String gpuClass, String gameKey) {
        List<Object> result = new ArrayList<>();
        result.add(base.get(0));
        result.add(base.get(1));
        result.add(base.get(2));
        result.add(base.get(3));
        result.add(base.get(4));
        result.add(base.get(5));
        result.add(base.get(5));
        result.add(base.get(5));
        result.add(gpuId);
        result.add(gpuClass);
        if (gameKey != null) {
            result.add(gameKey);
        }
        return result;
    }

    /** Reads the first active compatibility rule for a Tool seed fallback. */
    private Map<String, Object> ruleFor(String toolName) {
        String category = categoryForTool(toolName);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT result_status AS status,
                       message AS summary
                FROM compatibility_rules
                WHERE category = ?
                  AND deleted_at IS NULL
                ORDER BY id
                LIMIT 1
                """, category);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Finds representative Tool-ready part ids for seed fallback details. */
    private List<String> toolReadyPartIds(String category, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT public_id::text
                FROM parts
                WHERE category = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                  AND coalesce((attributes->>'toolReady')::boolean, false) = true
                ORDER BY price ASC, id ASC
                LIMIT ?
                """, String.class, category, limit);
    }

    /** Converts a DB row into the shared Tool part DTO. */
    private ToolBuildPart part(Map<String, Object> row) {
        return new ToolBuildPart(
                numberLong(row.get("internal_id")),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                DbValueMapper.integer(row, "price"),
                objectMap(DbValueMapper.json(row, "attributes", Map.of())),
                // DB 경로들엔 quantity 컬럼이 없다(build_items 포함) — null → 1개로 계산된다.
                DbValueMapper.integer(row, "quantity")
        );
    }

    /** Builds the common Tool response shape. */
    private static Map<String, Object> tool(String tool, String status, String confidence, String summary, Map<String, Object> details) {
        return MockData.map("tool", tool, "status", status, "confidence", confidence, "summary", summary, "details", details);
    }

    /** Reads the numeric benchmark score for a nullable part. */
    private static Double benchmarkScore(Map<Long, Map<String, Object>> benchmarkRows, ToolBuildPart part) {
        if (part == null || part.internalId() == null) {
            return null;
        }
        Map<String, Object> row = benchmarkRows.get(part.internalId());
        return row == null ? null : decimalValue(row.get("score"));
    }

    /** Reads the benchmark summary for a nullable part. */
    private static String benchmarkSummary(Map<Long, Map<String, Object>> benchmarkRows, ToolBuildPart part) {
        if (part == null || part.internalId() == null) {
            return null;
        }
        Map<String, Object> row = benchmarkRows.get(part.internalId());
        return row == null ? null : DbValueMapper.string(row, "summary");
    }

    /** Converts a public FPS benchmark row into Tool response details. */
    private static Map<String, Object> gameFpsEvidenceMap(
            Map<String, Object> row,
            String selectedGpuClass,
            String selectedCpuClass,
            String requestedGameKey,
            String requestedResolution
    ) {
        Map<String, Object> metadata = objectMap(DbValueMapper.json(row, "metadata", Map.of()));
        String sourceGameKey = DbValueMapper.string(row, "game_key");
        String sourceResolution = DbValueMapper.string(row, "resolution");
        Object sourceCpuClass = metadata.get("cpuClass");
        Object sourceGpuClass = metadata.get("gpuClass");
        boolean gameMatched = requestedGameKey == null || requestedGameKey.equals(sourceGameKey);
        boolean resolutionMatched = requestedResolution == null || requestedResolution.equals(sourceResolution);
        boolean cpuClassMatched = selectedCpuClass != null && selectedCpuClass.equals(String.valueOf(sourceCpuClass));
        boolean gpuClassMatched = selectedGpuClass != null && selectedGpuClass.equals(String.valueOf(sourceGpuClass));
        boolean exactCpuPartMatched = intRank(row.get("cpu_match_rank")) == 0;
        boolean exactGpuPartMatched = intRank(row.get("gpu_match_rank")) == 0;
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "gameTitle", DbValueMapper.string(row, "game_title"),
                "gameKey", sourceGameKey,
                "resolution", sourceResolution,
                "graphicsPreset", DbValueMapper.string(row, "graphics_preset"),
                "avgFps", decimalValue(row.get("avg_fps")),
                "onePercentLowFps", decimalValue(row.get("one_percent_low_fps")),
                "sourceName", DbValueMapper.string(row, "source_name"),
                "sourceUrl", DbValueMapper.string(row, "source_url"),
                "sourceCheckedAt", DbValueMapper.string(row, "source_checked_at"),
                "confidence", DbValueMapper.string(row, "confidence"),
                "match", MockData.map(
                        "requestedGameKey", requestedGameKey,
                        "requestedResolution", requestedResolution,
                        "selectedCpuClass", selectedCpuClass,
                        "selectedGpuClass", selectedGpuClass,
                        "sourceCpuClass", sourceCpuClass,
                        "sourceGpuClass", sourceGpuClass,
                        "hardwareScope", metadata.get("hardwareScope"),
                        "gameMatched", gameMatched,
                        "resolutionMatched", resolutionMatched,
                        "cpuClassMatched", cpuClassMatched,
                        "gpuClassMatched", gpuClassMatched,
                        "exactCpuPartMatched", exactCpuPartMatched,
                        "exactGpuPartMatched", exactGpuPartMatched,
                        "evidenceExactness", evidenceExactness(gameMatched, resolutionMatched, cpuClassMatched, gpuClassMatched, exactCpuPartMatched, exactGpuPartMatched)
                ),
                "sourceContext", MockData.map(
                        "sourceCpuName", metadata.get("sourceCpuName"),
                        "sourceGpuName", metadata.get("sourceGpuName"),
                        "sourceResolutionText", metadata.get("sourceResolutionText"),
                        "sourcePresetText", metadata.get("sourcePresetText"),
                        "gameVersion", metadata.get("gameVersion"),
                        "driverVersion", metadata.get("driverVersion"),
                        "upscaling", metadata.get("upscaling"),
                        "frameGeneration", metadata.get("frameGeneration"),
                        "rayTracing", metadata.get("rayTracing")
                ),
                "notes", metadata.get("notes"),
                "guaranteePolicy", metadata.getOrDefault("guaranteePolicy", "NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE")
        );
    }

    /** Indexes selected parts by category. */
    private static Map<String, ToolBuildPart> byCategory(List<ToolBuildPart> parts) {
        Map<String, ToolBuildPart> result = new LinkedHashMap<>();
        for (ToolBuildPart part : parts) {
            result.put(part.category(), part);
        }
        return result;
    }

    /** Determines whether a request is gaming-related enough to attach public FPS evidence. */
    private static boolean gamingContext(Map<String, Object> context) {
        String text = contextText(context);
        return containsAny(text, "game", "gaming", "게임", "qhd", "fhd", "4k", "144hz", "fps");
    }

    /** Extracts a canonical game key from Tool request context. */
    private static String gameKey(Map<String, Object> context) {
        String text = contextText(context);
        if (containsAny(text, "배그", "pubg", "battleground", "playerunknown")) return "pubg";
        if (containsAny(text, "로아", "로스트아크", "lost ark")) return "lost-ark";
        if (containsAny(text, "발로", "발로란트", "valorant")) return "valorant";
        if (containsAny(text, "오버워치", "overwatch")) return "overwatch-2";
        if (containsAny(text, "사이버펑크", "사펑", "cyberpunk")) return "cyberpunk-2077";
        return null;
    }

    /** Extracts canonical resolution labels used by game_fps_benchmarks. */
    private static String resolution(Map<String, Object> context) {
        String text = contextText(context);
        if (containsAny(text, "4k", "uhd", "2160")) return "4K";
        if (containsAny(text, "qhd", "1440", "2560")) return "QHD";
        if (containsAny(text, "fhd", "1080", "1920")) return "FHD";
        return null;
    }

    /** Builds a compact searchable context string from request fields. */
    private static String contextText(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        List<String> keys = List.of("gameTitle", "game", "targetGame", "gameName", "resolution", "rawMessage", "message", "requirementsText", "usageTags");
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null) {
                builder.append(' ').append(value);
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    /** Maps selected internal part names to the benchmark class labels stored in metadata. */
    private static String hardwareClass(ToolBuildPart part) {
        if (part == null) {
            return null;
        }
        String attrClass = firstText(stringAttr(part, "hardwareClass"), stringAttr(part, "gpuClass"));
        attrClass = firstText(attrClass, stringAttr(part, "cpuClass"));
        if (attrClass != null && !attrClass.isBlank()) {
            return attrClass;
        }
        String name = firstText(name(part), "").toLowerCase(Locale.ROOT);
        if ("GPU".equals(part.category())) {
            if (name.contains("5090")) return "RTX_5090";
            if (name.contains("5080")) return "RTX_5080";
            if (name.matches(".*5070\\s*ti.*") || name.contains("5070ti")) return "RTX_5070_TI";
            if (name.contains("5070")) return "RTX_5070";
            if (name.matches(".*5060\\s*ti.*") || name.contains("5060ti")) return "RTX_5060_TI";
            if (name.contains("5060")) return "RTX_5060";
        }
        if ("CPU".equals(part.category())) {
            if (name.contains("9950x3d")) return "RYZEN_9_9950X3D";
            if (name.contains("9950x")) return "RYZEN_9_9950X";
            if (name.contains("9900x3d")) return "RYZEN_9_9900X3D";
            if (name.contains("9800x3d")) return "RYZEN_7_9800X3D";
            if (name.contains("9700x")) return "RYZEN_7_9700X";
            if (name.contains("9600x")) return "RYZEN_5_9600X";
            if (name.contains("285k")) return "INTEL_CORE_ULTRA_9_285K";
            if (name.contains("265k")) return "INTEL_CORE_ULTRA_7_265K";
            if (name.contains("245k")) return "INTEL_CORE_ULTRA_5_245K";
        }
        return null;
    }

    /** Classifies the FPS evidence set so AI callers do not overstate fallback data. */
    private static String gameFpsEvidenceStatus(Map<String, Object> context, List<Map<String, Object>> evidence) {
        if (!gamingContext(context)) {
            return "NOT_GAMING_CONTEXT";
        }
        if (evidence == null || evidence.isEmpty()) {
            return "NO_MATCH";
        }
        String requestedGame = gameKey(context);
        String requestedResolution = resolution(context);
        if (requestedGame == null) {
            return "GENERAL_GAME_REFERENCE";
        }
        boolean hasExactResolution = requestedResolution == null || evidence.stream()
                .map(item -> objectMap(item.get("match")))
                .anyMatch(match -> Boolean.TRUE.equals(match.get("resolutionMatched")));
        return hasExactResolution ? "MATCHED" : "RESOLUTION_FALLBACK";
    }

    /** Grades whether an FPS row is exact evidence or only a nearby public reference. */
    private static String evidenceExactness(
            boolean gameMatched,
            boolean resolutionMatched,
            boolean cpuClassMatched,
            boolean gpuClassMatched,
            boolean exactCpuPartMatched,
            boolean exactGpuPartMatched
    ) {
        if (gameMatched && resolutionMatched && exactCpuPartMatched && exactGpuPartMatched) {
            return "EXACT_PART_AND_RESOLUTION";
        }
        if (gameMatched && resolutionMatched && cpuClassMatched && gpuClassMatched) {
            return "SAME_CLASS_AND_RESOLUTION";
        }
        if (gameMatched && gpuClassMatched) {
            return resolutionMatched ? "GPU_CLASS_REFERENCE" : "GPU_CLASS_RESOLUTION_FALLBACK";
        }
        if (gameMatched) {
            return resolutionMatched ? "GAME_RESOLUTION_REFERENCE" : "GAME_REFERENCE_FALLBACK";
        }
        return "GENERAL_REFERENCE";
    }

    /** Case-insensitive substring matcher for compact context extraction. */
    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Sums current selected part prices weighted by quantity — UI 총액과 같은 규칙. */
    private static int total(List<ToolBuildPart> parts) {
        return parts.stream()
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * part.effectiveQuantity())
                .sum();
    }

    /** Estimates continuous system draw for power checks — 수량 가중(스틱 검사와 같은 원칙). */
    private static int estimatedWattage(List<ToolBuildPart> parts) {
        return parts.stream()
                .mapToInt(part -> estimatedPartPowerDraw(part) * (part == null ? 1 : part.effectiveQuantity()))
                .sum() + 60;
    }

    /** Estimates per-part draw using spec attributes. */
    private static int estimatedPartPowerDraw(ToolBuildPart part) {
        if (part == null) {
            return 0;
        }
        return switch (part.category()) {
            case "CPU" -> Math.max(intAttr(part, "wattage", 0), intAttr(part, "tdpW", 65));
            case "GPU" -> intAttr(part, "wattage", 0);
            case "MOTHERBOARD" -> intAttr(part, "wattage", 50);
            case "RAM" -> intAttr(part, "wattage", 10);
            case "STORAGE" -> intAttr(part, "wattage", 8);
            case "COOLER" -> firstPositive(intAttr(part, "electricalW", 0), intAttr(part, "pumpW", 0), intAttr(part, "fanW", 0), 8);
            case "CASE" -> firstPositive(intAttr(part, "fanW", 0), intAttr(part, "wattage", 0), 10);
            case "PSU" -> 0;
            default -> intAttr(part, "wattage", 0);
        };
    }

    /** Returns the first positive numeric candidate. */
    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    /** Checks cooler socket support arrays. */
    private static boolean socketSupported(ToolBuildPart cooler, String socket) {
        if (socket == null || cooler == null) {
            return true;
        }
        Object support = cooler.attributes().get("socketSupport");
        if (support instanceof List<?> list) {
            return list.stream().anyMatch(item -> socket.equalsIgnoreCase(String.valueOf(item)));
        }
        return true;
    }

    /** Compares nullable attribute strings permissively. */
    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return true;
        }
        return left.equalsIgnoreCase(right);
    }

    /** Reads a string attribute from a part — 앞뒤 공백을 제거하고 빈 문자열은 결측(null)으로 본다. */
    private static String stringAttr(ToolBuildPart part, String key) {
        if (part == null) {
            return null;
        }
        return text(part.attributes().get(key));
    }

    /** Reads a boolean attribute treating absent or non-true values as false. */
    private static boolean boolAttr(ToolBuildPart part, String key) {
        if (part == null) {
            return false;
        }
        Object value = part.attributes().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(text(value));
    }

    /** Reads an integer-array attribute (예: 케이스 radiatorSupportMm) — 배열이 아니면 빈 목록. */
    private static List<Integer> intListAttr(ToolBuildPart part, String key) {
        if (part == null) {
            return List.of();
        }
        Object value = part.attributes().get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(ToolCheckService::numberValue)
                    .filter(item -> item != null && item > 0)
                    .toList();
        }
        return List.of();
    }

    /** Reads an integer attribute from a part. */
    private static int intAttr(ToolBuildPart part, String key, int fallback) {
        if (part == null) {
            return fallback;
        }
        Object value = part.attributes().get(key);
        Integer parsed = numberValue(value);
        return parsed == null ? fallback : parsed;
    }

    /** Resolves category ownership for seed compatibility rules. */
    private static String categoryForTool(String toolName) {
        return switch (toolName) {
            case "compatibility" -> "MOTHERBOARD";
            case "power" -> "PSU";
            case "size" -> "CASE";
            case "performance" -> "GPU";
            case "price" -> "GPU";
            default -> "GPU";
        };
    }

    /** Supplies legacy fallback statuses for seed Tool calls. */
    private static String defaultStatus(String toolName) {
        return "compatibility".equals(toolName) || "size".equals(toolName) ? "PASS" : "WARN";
    }

    /** Normalizes supported Tool names from route input. */
    private static String normalizeToolName(String value) {
        String tool = text(value);
        if (tool == null) {
            throw new IllegalArgumentException("Tool 이름이 필요합니다.");
        }
        tool = tool.toLowerCase(Locale.ROOT);
        if (!TOOL_ORDER.contains(tool)) {
            throw new IllegalArgumentException("지원하지 않는 Tool입니다: " + value);
        }
        return tool;
    }

    /** Converts arbitrary map values into a string-keyed map. */
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return new LinkedHashMap<>();
    }

    /** Converts arbitrary values into trimmed string lists. */
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        String text = text(value);
        if (text == null) {
            return List.of();
        }
        return List.of(text.split(",")).stream().map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    /** Reads text values while treating blanks and null text as absent. */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    /** Picks the first nonblank text value. */
    private static String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    /** Picks a parsed number or a fallback. */
    private static int firstNumber(Object value, int fallback) {
        Integer parsed = numberValue(value);
        return parsed == null ? fallback : parsed;
    }

    /** Parses an integer-like value — 비숫자 문자열("2개" 등)은 예외 대신 null(결측=검사 생략 관례). */
    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Parses decimal-like DB values for benchmark scores — 비숫자 문자열은 null. */
    private static Double decimalValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Double.valueOf(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 부품 치수(mm) 읽기 — 소수는 올림(ceil)해 여유가 실제보다 커 보이지 않게 한다. 첫 유효 키를 쓴다. */
    private static int partDimensionMm(ToolBuildPart part, String... keys) {
        if (part == null) {
            return 0;
        }
        for (String key : keys) {
            Double value = decimalValue(part.attributes().get(key));
            if (value != null && value > 0) {
                return (int) Math.ceil(value);
            }
        }
        return 0;
    }

    /** 케이스 허용치(mm) 읽기 — 소수는 내림(floor)해 허용이 실제보다 커 보이지 않게 한다. */
    private static int caseLimitMm(ToolBuildPart part, String key) {
        if (part == null) {
            return 0;
        }
        Double value = decimalValue(part.attributes().get(key));
        return value == null || value <= 0 ? 0 : (int) Math.floor(value);
    }

    /** Reads match rank integers computed by SQL CASE expressions. */
    private static int intRank(Object value) {
        Integer parsed = numberValue(value);
        return parsed == null ? 99 : parsed;
    }

    /** Parses a long-like value. */
    private static Long numberLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    /** Reads the display name of a nullable part. */
    private static String name(ToolBuildPart part) {
        return part == null ? null : part.name();
    }

    private record AgentRootParts(List<ToolBuildPart> parts, Integer budget) {
    }
}
