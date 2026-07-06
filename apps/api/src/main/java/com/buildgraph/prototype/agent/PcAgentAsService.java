package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenHasher;
import com.buildgraph.prototype.support.AsLogRagAnalysisService;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PcAgentAsService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,160}");
    private static final int DEFAULT_ACTIVATION_TOKEN_TTL_DAYS = 7;
    private static final int MAX_ACTIVATION_TOKEN_TTL_DAYS = 7;
    private static final long MAX_GZIP_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_UNCOMPRESSED_BYTES = 20L * 1024L * 1024L;
    private static final Set<String> CONSENT_TYPES = Set.of(
            "LOCAL_COLLECTION",
            "SERVER_UPLOAD",
            "QUALITY_IMPROVEMENT",
            "REMOTE_CONNECTION",
            "REMOTE_FULL_CONTROL",
            "HIGH_RISK_REMOTE_ACTION"
    );
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final AgentTokenHasher tokenHasher;
    private final Clock clock;
    private final Supplier<String> tokenGenerator;

    @Autowired
    public PcAgentAsService(JdbcTemplate jdbcTemplate, AgentTokenHasher tokenHasher) {
        this(jdbcTemplate, tokenHasher, Clock.systemUTC(), PcAgentAsService::newAgentToken);
    }

    PcAgentAsService(
            JdbcTemplate jdbcTemplate,
            AgentTokenHasher tokenHasher,
            Clock clock,
            Supplier<String> tokenGenerator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
        this.tokenGenerator = tokenGenerator;
    }

    @Transactional
    public Map<String, Object> issueActivationToken(Map<String, Object> request) {
        Long userInternalId = resolveActivationTokenUser(request);
        int ttlDays = integer(request, "ttlDays", DEFAULT_ACTIVATION_TOKEN_TTL_DAYS);
        if (ttlDays < 1 || ttlDays > MAX_ACTIVATION_TOKEN_TTL_DAYS) {
            throw badRequest("ttlDays must be between 1 and 7.");
        }

        String rawActivationToken = newAgentToken();
        String tokenHash = tokenHasher.sha256Hex(rawActivationToken);
        Instant expiresAt = Instant.now(clock).plus(Duration.ofDays(ttlDays));
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    INSERT INTO agent_activation_tokens (
                      user_id,
                      token_hash,
                      expires_at
                    )
                    VALUES (?, ?, ?)
                    RETURNING public_id::text AS id, expires_at
                    """,
                    userInternalId,
                    tokenHash,
                    Timestamp.from(expiresAt)
            );
            return MockData.map(
                    "id", DbValueMapper.string(row, "id"),
                    "activationToken", rawActivationToken,
                    "tokenType", "Activation",
                    "expiresAt", DbValueMapper.timestamp(row, "expires_at")
            );
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent activation token collision.", exception);
        }
    }

    @Transactional
    public Map<String, Object> issueActivationTokenForUser(CurrentUserService.CurrentUser user) {
        return issueActivationToken(MockData.map(
                "userId", user.id(),
                "ttlDays", 1
        ));
    }

    private Long resolveActivationTokenUser(Map<String, Object> request) {
        String userId = string(request, "userId", null);
        String userEmail = string(request, "userEmail", null);
        if (userId == null && userEmail == null) {
            throw badRequest("userId or userEmail is required.");
        }
        if (userId != null) {
            validateUuid("userId", userId);
        }
        List<Map<String, Object>> rows = userId != null
                ? jdbcTemplate.queryForList("""
                        SELECT id
                        FROM users
                        WHERE public_id = ?::uuid
                          AND deleted_at IS NULL
                        """, userId)
                : jdbcTemplate.queryForList("""
                        SELECT id
                        FROM users
                        WHERE email = ?
                          AND deleted_at IS NULL
                        """, userEmail);
        return rows.stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Activation token user not found."));
    }

    @Transactional
    public Map<String, Object> register(Map<String, Object> request) {
        String activationToken = requiredString(request, "activationToken");
        String deviceFingerprintHash = requiredString(request, "deviceFingerprintHash");
        String hostnameHash = string(request, "hostnameHash", null);
        String registrationKey = requiredString(request, "registrationIdempotencyKey");
        validateIdempotencyKey("registrationIdempotencyKey", registrationKey);
        String osVersion = requiredString(request, "osVersion");
        String agentVersion = requiredString(request, "agentVersion");
        String policyVersion = requiredString(request, "policyVersion");
        ActivationToken activation = verifyActivationToken(activationToken, registrationKey);

        String rawAgentToken = tokenGenerator.get();
        if (rawAgentToken == null || rawAgentToken.isBlank()) {
            throw new IllegalStateException("Generated agent token must not be blank.");
        }
        String tokenHash = tokenHasher.sha256Hex(rawAgentToken);

        Map<String, Object> row = refreshExistingRegistration(
                activation.activationTokenId(),
                registrationKey,
                tokenHash,
                deviceFingerprintHash,
                hostnameHash,
                osVersion,
                agentVersion,
                policyVersion
        );
        if (row == null) {
            row = refreshExistingActiveRegistration(
                    activation.userInternalId(),
                    activation.activationTokenId(),
                    deviceFingerprintHash,
                    hostnameHash,
                    tokenHash,
                    registrationKey,
                    osVersion,
                    agentVersion,
                    policyVersion
            );
        }
        if (row == null) {
            markActivationTokenUsed(activation.activationTokenId());
            row = insertRegistration(
                    activation.userInternalId(),
                    activation.activationTokenId(),
                    deviceFingerprintHash,
                    hostnameHash,
                    tokenHash,
                    registrationKey,
                    osVersion,
                    agentVersion,
                    policyVersion
            );
        }

        return MockData.map(
                "deviceId", DbValueMapper.string(row, "device_id"),
                "status", DbValueMapper.string(row, "status"),
                "agentToken", rawAgentToken,
                "tokenType", "Bearer"
        );
    }

    private ActivationToken verifyActivationToken(String rawActivationToken, String registrationKey) {
        String activationTokenHash = tokenHasher.sha256Hex(rawActivationToken);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT at.id AS activation_token_id,
                       at.user_id,
                       at.expires_at,
                       at.used_at,
                       at.revoked_at,
                       d.id AS existing_device_internal_id
                FROM agent_activation_tokens at
                LEFT JOIN agent_devices d
                  ON d.activation_token_id = at.id
                 AND d.registration_idempotency_key = ?
                 AND d.status IN ('PENDING_REGISTERED', 'ACTIVE', 'UPDATE_REQUIRED')
                WHERE at.token_hash = ?
                ORDER BY d.id DESC NULLS LAST
                LIMIT 1
                """, registrationKey, activationTokenHash);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent activation token is invalid.");
        }

        Map<String, Object> row = rows.get(0);
        if (row.get("revoked_at") != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent activation token is invalid.");
        }
        Instant expiresAt = instantValue(row, "expires_at");
        if (expiresAt == null || !expiresAt.isAfter(Instant.now(clock))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent activation token is invalid.");
        }
        if (row.get("used_at") != null && row.get("existing_device_internal_id") == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent activation token is already used.");
        }
        return new ActivationToken(
                longValue(row, "activation_token_id"),
                longValue(row, "user_id")
        );
    }

    private void markActivationTokenUsed(Long activationTokenId) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_activation_tokens
                SET used_at = COALESCE(used_at, now())
                WHERE id = ?
                  AND used_at IS NULL
                  AND revoked_at IS NULL
                  AND expires_at > now()
                """, activationTokenId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent activation token is already used.");
        }
    }

    private Map<String, Object> refreshExistingRegistration(
            Long activationTokenId,
            String registrationKey,
            String tokenHash,
            String deviceFingerprintHash,
            String hostnameHash,
            String osVersion,
            String agentVersion,
            String policyVersion
    ) {
        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList("""
                SELECT id AS device_internal_id,
                       public_id::text AS device_id,
                       status
                FROM agent_devices
                WHERE activation_token_id = ?
                  AND registration_idempotency_key = ?
                  AND status IN ('PENDING_REGISTERED', 'ACTIVE', 'UPDATE_REQUIRED')
                ORDER BY id DESC
                LIMIT 1
                """, activationTokenId, registrationKey);
        if (existingRows.isEmpty()) {
            return null;
        }

        Long deviceInternalId = longValue(existingRows.get(0), "device_internal_id");
        return jdbcTemplate.queryForMap("""
                UPDATE agent_devices
                SET device_fingerprint_hash = ?,
                    hostname_hash = ?,
                    agent_token_hash = ?,
                    status = 'ACTIVE',
                    os_version = ?,
                    agent_version = ?,
                    policy_version = ?,
                    updated_at = now()
                WHERE id = ?
                RETURNING id AS device_internal_id, public_id::text AS device_id, status
                """,
                deviceFingerprintHash,
                hostnameHash,
                tokenHash,
                osVersion,
                agentVersion,
                policyVersion,
                deviceInternalId
        );
    }

    private Map<String, Object> refreshExistingActiveRegistration(
            Long userInternalId,
            Long activationTokenId,
            String deviceFingerprintHash,
            String hostnameHash,
            String tokenHash,
            String registrationKey,
            String osVersion,
            String agentVersion,
            String policyVersion
    ) {
        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList("""
                SELECT id AS device_internal_id
                FROM agent_devices
                WHERE user_id = ?
                  AND status IN ('PENDING_REGISTERED', 'ACTIVE', 'UPDATE_REQUIRED')
                ORDER BY id DESC
                LIMIT 1
                """, userInternalId);
        if (existingRows.isEmpty()) {
            return null;
        }

        markActivationTokenUsed(activationTokenId);
        Long deviceInternalId = longValue(existingRows.get(0), "device_internal_id");
        return jdbcTemplate.queryForMap("""
                UPDATE agent_devices
                SET activation_token_id = ?,
                    registration_idempotency_key = ?,
                    device_fingerprint_hash = ?,
                    hostname_hash = ?,
                    agent_token_hash = ?,
                    status = 'ACTIVE',
                    os_version = ?,
                    agent_version = ?,
                    policy_version = ?,
                    updated_at = now()
                WHERE id = ?
                RETURNING id AS device_internal_id, public_id::text AS device_id, status
                """,
                activationTokenId,
                registrationKey,
                deviceFingerprintHash,
                hostnameHash,
                tokenHash,
                osVersion,
                agentVersion,
                policyVersion,
                deviceInternalId
        );
    }

    private Map<String, Object> insertRegistration(
            Long userInternalId,
            Long activationTokenId,
            String deviceFingerprintHash,
            String hostnameHash,
            String tokenHash,
            String registrationKey,
            String osVersion,
            String agentVersion,
            String policyVersion
    ) {
        try {
            return jdbcTemplate.queryForMap("""
                    INSERT INTO agent_devices (
                      user_id,
                      activation_token_id,
                      device_fingerprint_hash,
                      hostname_hash,
                      agent_token_hash,
                      registration_idempotency_key,
                      status,
                      os_version,
                      agent_version,
                      policy_version,
                      updated_at
                    )
                    VALUES (
                      ?,
                      ?,
                      ?,
                      ?,
                      ?,
                      ?,
                      'ACTIVE',
                      ?,
                      ?,
                      ?,
                      now()
                    )
                    RETURNING id AS device_internal_id, public_id::text AS device_id, status
                    """,
                    userInternalId,
                    activationTokenId,
                    deviceFingerprintHash,
                    hostnameHash,
                    tokenHash,
                    registrationKey,
                    osVersion,
                    agentVersion,
                    policyVersion
            );
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent device is already registered.", exception);
        }
    }

    @Transactional
    public Map<String, Object> saveConsent(
            AgentPrincipal principal,
            Map<String, Object> request,
            String idempotencyKey
    ) {
        validateIdempotencyKey("Idempotency-Key", idempotencyKey);
        String consentType = requiredString(request, "consentType");
        if (!CONSENT_TYPES.contains(consentType)) {
            throw badRequest("consentType is invalid.");
        }
        String policyVersion = requiredString(request, "policyVersion");
        boolean accepted = requiredBoolean(request, "accepted");
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO agent_consents (
                  user_id,
                  device_id,
                  consent_type,
                  policy_version,
                  source,
                  idempotency_key,
                  accepted,
                  accepted_at,
                  revoked_at
                )
                VALUES (
                  ?,
                  ?,
                  ?,
                  ?,
                  'AGENT',
                  ?,
                  ?,
                  CASE WHEN ? THEN now() ELSE NULL END,
                  CASE WHEN ? THEN NULL ELSE now() END
                )
                RETURNING public_id::text AS id, consent_type, policy_version, accepted, accepted_at, revoked_at
                """,
                principal.userInternalId(),
                principal.deviceInternalId(),
                consentType,
                policyVersion,
                idempotencyKey,
                accepted,
                accepted,
                accepted
        );
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "consentType", DbValueMapper.string(row, "consent_type"),
                "policyVersion", DbValueMapper.string(row, "policy_version"),
                "accepted", row.get("accepted"),
                "acceptedAt", DbValueMapper.timestamp(row, "accepted_at"),
                "revokedAt", DbValueMapper.timestamp(row, "revoked_at")
        );
    }

    @Transactional
    public Map<String, Object> heartbeat(
            AgentPrincipal principal,
            Map<String, Object> request,
            String idempotencyKey
    ) {
        validateIdempotencyKey("Idempotency-Key", idempotencyKey);
        String agentVersion = requiredString(request, "agentVersion");
        String serviceStatus = requiredString(request, "serviceStatus");
        String policyVersion = string(request, "policyVersion", null);
        Map<String, Object> deviceRow = updateHeartbeatDevice(principal, agentVersion, policyVersion);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO agent_heartbeats (
                  device_id,
                  agent_version,
                  service_status,
                  tray_status,
                  policy_version,
                  idempotency_key,
                  received_at
                )
                VALUES (?, ?, ?, ?, ?, ?, now())
                RETURNING public_id::text AS id, received_at
                """,
                principal.deviceInternalId(),
                agentVersion,
                serviceStatus,
                string(request, "trayStatus", null),
                policyVersion,
                idempotencyKey
        );

        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "deviceId", principal.deviceId(),
                "status", DbValueMapper.string(deviceRow, "status"),
                "lastSeenAt", DbValueMapper.timestamp(deviceRow, "last_seen_at"),
                "receivedAt", DbValueMapper.timestamp(row, "received_at"),
                "pendingCommands", java.util.List.of()
        );
    }

    private Map<String, Object> updateHeartbeatDevice(
            AgentPrincipal principal,
            String agentVersion,
            String policyVersion
    ) {
        try {
            return jdbcTemplate.queryForMap("""
                    UPDATE agent_devices
                    SET last_seen_at = now(),
                        agent_version = ?,
                        policy_version = COALESCE(?, policy_version),
                        updated_at = now()
                    WHERE id = ?
                      AND user_id = ?
                      AND status IN ('ACTIVE', 'UPDATE_REQUIRED')
                    RETURNING status, last_seen_at
                    """,
                    agentVersion,
                    policyVersion,
                    principal.deviceInternalId(),
                    principal.userInternalId()
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent device is not active.", exception);
        }
    }

    @Transactional
    public Map<String, Object> previewAsRag(
            AgentPrincipal principal,
            MultipartFile file,
            Map<String, Object> metadata,
            String idempotencyKey
    ) {
        validateIdempotencyKey("Idempotency-Key", idempotencyKey);
        if (file == null || file.isEmpty()) {
            throw fileValidation("Agent log preview gzip file is required.");
        }
        String fileName = fileName(file);
        if (!fileName.endsWith(".gz")) {
            throw fileValidation("Agent log preview must be gzip.");
        }

        PcAgentLogAnalyzer.IncidentWindow incidentWindow = PcAgentLogAnalyzer.resolveIncidentWindow(metadata, clock);
        GzipValidation gzip = validateGzip(file);
        PcAgentLogAnalyzer.validateJsonl(gzip.contentText(), incidentWindow);
        Integer consentCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM agent_consents
                WHERE device_id = ?
                  AND consent_type = 'SERVER_UPLOAD'
                  AND accepted = true
                  AND revoked_at IS NULL
                """, Integer.class, principal.deviceInternalId());
        if (consentCount == null || consentCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Server upload consent is required.");
        }

        Map<String, Object> analysis = new AsLogRagAnalysisService(jdbcTemplate)
                .analyze(file, incidentWindow.durationMinutes());
        Map<String, Object> response = new LinkedHashMap<>(analysis);
        response.put("previewSource", "PC_AGENT_LOG_UPLOAD");
        response.put("rangeMinutes", incidentWindow.durationMinutes());
        response.put("incidentWindow", incidentWindow.toMap());
        response.put("rawSamplesCount", listSize(response.get("rawSamples")));
        return response;
    }

    @Transactional
    public Map<String, Object> uploadLogs(
            AgentPrincipal principal,
            MultipartFile file,
            Map<String, Object> metadata,
            String idempotencyKey
    ) {
        validateIdempotencyKey("Idempotency-Key", idempotencyKey);
        if (file == null || file.isEmpty()) {
            throw fileValidation("Agent log gzip file is required.");
        }
        String fileName = fileName(file);
        if (!fileName.endsWith(".gz")) {
            throw fileValidation("Agent log upload must be gzip.");
        }
        GzipValidation gzip = validateGzip(file);
        String symptom = string(metadata, "symptom", "Agent uploaded diagnostic log.");
        PcAgentLogAnalyzer.IncidentWindow incidentWindow = PcAgentLogAnalyzer.resolveIncidentWindow(metadata, clock);
        PcAgentLogAnalyzer.RawLogBundle rawLogs = PcAgentLogAnalyzer.validateJsonl(gzip.contentText(), incidentWindow);
        PcAgentLogAnalyzer.AnalysisResult analysis = PcAgentLogAnalyzer.analyze(principal, symptom, incidentWindow, rawLogs);
        int rangeMinutes = incidentWindow.durationMinutes();
        int schemaVersion = analysis.schemaVersion();
        Map<String, Object> existingUpload = existingUploadResult(
                principal,
                idempotencyKey,
                gzip.sha256(),
                rangeMinutes,
                schemaVersion,
                symptom,
                incidentWindow
        );
        if (existingUpload != null) {
            return existingUpload;
        }
        Integer consentCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM agent_consents
                WHERE device_id = ?
                  AND consent_type = 'SERVER_UPLOAD'
                  AND accepted = true
                  AND revoked_at IS NULL
                """, Integer.class, principal.deviceInternalId());
        if (consentCount == null || consentCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Server upload consent is required.");
        }

        Map<String, Object> uploadJob = jdbcTemplate.queryForMap("""
                INSERT INTO agent_upload_jobs (
                  device_id,
                  idempotency_key,
                  status,
                  range_started_at,
                  range_ended_at,
                  updated_at
                )
                VALUES (?, ?, 'UPLOADED', ?, ?, now())
                RETURNING id AS upload_job_internal_id, public_id::text AS upload_job_id, status
                """,
                principal.deviceInternalId(),
                idempotencyKey,
                Timestamp.from(incidentWindow.startedAt()),
                Timestamp.from(incidentWindow.endedAt())
        );

        Long uploadJobInternalId = longValue(uploadJob, "upload_job_internal_id");
        String storagePath = "agent-logs/" + principal.deviceId() + "/" + fileName;
        String incidentWindowJson = toJson(incidentWindow.toMap());
        Map<String, Object> logUpload = jdbcTemplate.queryForMap("""
                INSERT INTO agent_log_uploads (
                  user_id,
                  device_id,
                  upload_job_id,
                  range_minutes,
                  status,
                  file_name,
                  file_size,
                  storage_path,
                  summary,
                  incident_window,
                  range_started_at,
                  range_ended_at,
                  consent_accepted_at,
                  delete_after
                )
                VALUES (?, ?, ?, ?, 'UPLOADED', ?, ?, ?, ?, ?::jsonb, ?, ?, now(), now() + interval '30 days')
                RETURNING id AS log_upload_internal_id,
                          public_id::text AS log_upload_id,
                          status,
                          file_name,
                          file_size,
                          range_minutes,
                          delete_after
                """,
                principal.userInternalId(),
                principal.deviceInternalId(),
                uploadJobInternalId,
                rangeMinutes,
                fileName,
                gzip.compressedBytes(),
                storagePath,
                analysis.summaryText(),
                incidentWindowJson,
                Timestamp.from(incidentWindow.startedAt()),
                Timestamp.from(incidentWindow.endedAt())
        );

        Long logUploadInternalId = longValue(logUpload, "log_upload_internal_id");
        Object deleteAfter = DbValueMapper.timestamp(logUpload, "delete_after");
        jdbcTemplate.queryForMap("""
                INSERT INTO agent_log_bundles (
                  upload_job_id,
                  log_upload_id,
                  schema_version,
                  storage_path,
                  sha256,
                  size_bytes,
                  delete_after
                )
                VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, now() + interval '30 days'))
                RETURNING public_id::text AS log_bundle_id
                """,
                uploadJobInternalId,
                logUploadInternalId,
                schemaVersion,
                storagePath,
                gzip.sha256(),
                gzip.compressedBytes(),
                timestampParameter(logUpload.get("delete_after"))
        );
        String ticketPublicId = UUID.randomUUID().toString();
        Map<String, Object> logSummary = PcAgentLogAnalyzer.withTicketId(analysis.logSummary(), ticketPublicId);
        Map<String, Object> supportRouting = analysis.supportRouting();
        @SuppressWarnings("unchecked")
        Map<String, Object> userSymptom = (Map<String, Object>) logSummary.get("userSymptom");
        Map<String, Object> aiDiagnosisRequest = PcAgentLogAnalyzer.aiDiagnosisRequest(
                ticketPublicId,
                userSymptom,
                logSummary,
                supportRouting
        );
        String recommendedDecision = string(supportRouting, "recommendedDecision", "NEEDS_MORE_INFO");
        String riskLevel = riskLevelForRouting(recommendedDecision, supportRouting);
        String safetyAdviceLevel = string(supportRouting, "safetyAdviceLevel", "NONE");
        Object safetyNotices = supportRouting.getOrDefault("safetyNotices", List.of());
        Map<String, Object> ticket = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  public_id,
                  user_id,
                  log_upload_id,
                  symptom,
                  status,
                  analysis_status,
                  review_status,
                  support_decision,
                  risk_level,
                  auto_response_allowed,
                  cause_candidates,
                  upgrade_candidates,
                  admin_note,
                  incident_window,
                  log_summary,
                  support_routing,
                  ai_diagnosis_request,
                  safety_advice_level,
                  safety_notices,
                  updated_at
                )
                VALUES (
                  ?::uuid,
                  ?,
                  ?,
                  ?,
                  'OPEN',
                  'RULE_READY',
                  'REQUIRED',
                  ?,
                  ?,
                  false,
                  ?::jsonb,
                  ?::jsonb,
                  ?,
                  ?::jsonb,
                  ?::jsonb,
                  ?::jsonb,
                  ?::jsonb,
                  ?,
                  ?::jsonb,
                  now()
                )
                RETURNING public_id::text AS ticket_id,
                          status,
                          analysis_status,
                          review_status,
                          support_decision,
                          risk_level
                """,
                ticketPublicId,
                principal.userInternalId(),
                logUploadInternalId,
                symptom,
                recommendedDecision,
                riskLevel,
                toJson(causeCandidatesFrom(supportRouting)),
                toJson(List.of()),
                analysis.summaryText(),
                incidentWindowJson,
                toJson(logSummary),
                toJson(supportRouting),
                toJson(aiDiagnosisRequest),
                safetyAdviceLevel,
                toJson(safetyNotices)
        );

        return MockData.map(
                "uploadJobId", DbValueMapper.string(uploadJob, "upload_job_id"),
                "logUploadId", DbValueMapper.string(logUpload, "log_upload_id"),
                "ticketId", DbValueMapper.string(ticket, "ticket_id"),
                "status", DbValueMapper.string(ticket, "status"),
                "analysisStatus", DbValueMapper.string(ticket, "analysis_status"),
                "reviewStatus", DbValueMapper.string(ticket, "review_status"),
                "supportDecision", DbValueMapper.string(ticket, "support_decision"),
                "riskLevel", DbValueMapper.string(ticket, "risk_level"),
                "safetyAdviceLevel", safetyAdviceLevel,
                "safetyNotices", safetyNotices,
                "rangeMinutes", rangeMinutes,
                "incidentWindow", incidentWindow.toMap(),
                "supportRouting", supportRouting,
                "rawSamplesCount", ((List<?>) logSummary.get("rawSamples")).size(),
                "deleteAfter", deleteAfter
        );
    }

    @Transactional
    public Map<String, Object> createAsDraft(
            AgentPrincipal principal,
            MultipartFile file,
            Map<String, Object> metadata,
            String idempotencyKey
    ) {
        validateIdempotencyKey("Idempotency-Key", idempotencyKey);
        if (file == null || file.isEmpty()) {
            throw fileValidation("Agent log gzip file is required.");
        }
        String fileName = fileName(file);
        if (!fileName.endsWith(".gz")) {
            throw fileValidation("Agent log upload must be gzip.");
        }
        Map<String, Object> existing = existingDraftResult(principal, idempotencyKey);
        if (existing != null) {
            return existing;
        }
        GzipValidation gzip = validateGzip(file);
        String symptom = string(metadata, "symptom", "Agent detected PC issue.");
        PcAgentLogAnalyzer.IncidentWindow incidentWindow = PcAgentLogAnalyzer.resolveIncidentWindow(metadata, clock);
        PcAgentLogAnalyzer.RawLogBundle rawLogs = PcAgentLogAnalyzer.validateJsonl(gzip.contentText(), incidentWindow);
        PcAgentLogAnalyzer.AnalysisResult analysis = PcAgentLogAnalyzer.analyze(principal, symptom, incidentWindow, rawLogs);
        int rangeMinutes = incidentWindow.durationMinutes();
        int schemaVersion = analysis.schemaVersion();

        Integer consentCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM agent_consents
                WHERE device_id = ?
                  AND consent_type = 'SERVER_UPLOAD'
                  AND accepted = true
                  AND revoked_at IS NULL
                """, Integer.class, principal.deviceInternalId());
        if (consentCount == null || consentCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Server upload consent is required.");
        }

        Map<String, Object> uploadJob = jdbcTemplate.queryForMap("""
                INSERT INTO agent_upload_jobs (
                  device_id,
                  idempotency_key,
                  status,
                  range_started_at,
                  range_ended_at,
                  updated_at
                )
                VALUES (?, ?, 'UPLOADED', ?, ?, now())
                RETURNING id AS upload_job_internal_id, public_id::text AS upload_job_id, status
                """,
                principal.deviceInternalId(),
                idempotencyKey,
                Timestamp.from(incidentWindow.startedAt()),
                Timestamp.from(incidentWindow.endedAt())
        );

        Long uploadJobInternalId = longValue(uploadJob, "upload_job_internal_id");
        String storagePath = "agent-logs/" + principal.deviceId() + "/" + fileName;
        String incidentWindowJson = toJson(incidentWindow.toMap());
        Map<String, Object> logUpload = jdbcTemplate.queryForMap("""
                INSERT INTO agent_log_uploads (
                  user_id,
                  device_id,
                  upload_job_id,
                  range_minutes,
                  status,
                  file_name,
                  file_size,
                  storage_path,
                  summary,
                  incident_window,
                  range_started_at,
                  range_ended_at,
                  consent_accepted_at,
                  delete_after
                )
                VALUES (?, ?, ?, ?, 'UPLOADED', ?, ?, ?, ?, ?::jsonb, ?, ?, now(), now() + interval '30 days')
                RETURNING id AS log_upload_internal_id,
                          public_id::text AS log_upload_id,
                          status,
                          file_name,
                          file_size,
                          range_minutes,
                          delete_after
                """,
                principal.userInternalId(),
                principal.deviceInternalId(),
                uploadJobInternalId,
                rangeMinutes,
                fileName,
                gzip.compressedBytes(),
                storagePath,
                analysis.summaryText(),
                incidentWindowJson,
                Timestamp.from(incidentWindow.startedAt()),
                Timestamp.from(incidentWindow.endedAt())
        );

        Long logUploadInternalId = longValue(logUpload, "log_upload_internal_id");
        jdbcTemplate.queryForMap("""
                INSERT INTO agent_log_bundles (
                  upload_job_id,
                  log_upload_id,
                  schema_version,
                  storage_path,
                  sha256,
                  size_bytes,
                  delete_after
                )
                VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, now() + interval '30 days'))
                RETURNING public_id::text AS log_bundle_id
                """,
                uploadJobInternalId,
                logUploadInternalId,
                schemaVersion,
                storagePath,
                gzip.sha256(),
                gzip.compressedBytes(),
                timestampParameter(logUpload.get("delete_after"))
        );

        String title = string(metadata, "title", titleFor(incidentWindow.symptomType()));
        String detail = string(metadata, "detailDescription", detailFor(incidentWindow.symptomType()));
        String supportRequestKind = string(metadata, "supportRequestKind", "DIAGNOSIS_ONLY");
        Map<String, Object> draft = jdbcTemplate.queryForMap("""
                INSERT INTO as_ticket_drafts (
                  user_id,
                  device_id,
                  upload_job_id,
                  log_upload_id,
                  idempotency_key,
                  title,
                  detail_description,
                  symptom_type,
                  symptom,
                  detected_at,
                  incident_window,
                  support_request_kind,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
                RETURNING public_id::text AS draft_id,
                          title,
                          detail_description,
                          symptom_type,
                          symptom,
                          detected_at,
                          incident_window,
                          support_request_kind,
                          status,
                          created_at
                """,
                principal.userInternalId(),
                principal.deviceInternalId(),
                uploadJobInternalId,
                logUploadInternalId,
                idempotencyKey,
                title,
                detail,
                incidentWindow.symptomType(),
                symptom,
                Timestamp.from(incidentWindow.detectedAt()),
                incidentWindowJson,
                supportRequestKind
        );
        return draftMap(draft, DbValueMapper.string(logUpload, "log_upload_id"));
    }

    private Map<String, Object> existingDraftResult(AgentPrincipal principal, String idempotencyKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT d.public_id::text AS draft_id,
                       d.title,
                       d.detail_description,
                       d.symptom_type,
                       d.symptom,
                       d.detected_at,
                       d.incident_window,
                       d.support_request_kind,
                       d.status,
                       d.created_at,
                       lu.public_id::text AS log_upload_id
                FROM as_ticket_drafts d
                JOIN agent_log_uploads lu ON lu.id = d.log_upload_id
                WHERE d.device_id = ?
                  AND d.idempotency_key = ?
                ORDER BY d.id DESC
                LIMIT 1
                """, principal.deviceInternalId(), idempotencyKey);
        return rows.stream()
                .findFirst()
                .map(row -> draftMap(row, DbValueMapper.string(row, "log_upload_id")))
                .orElse(null);
    }

    private static Map<String, Object> draftMap(Map<String, Object> row, String logUploadId) {
        return MockData.map(
                "draftId", DbValueMapper.string(row, "draft_id"),
                "logUploadId", logUploadId,
                "title", DbValueMapper.string(row, "title"),
                "detailDescription", DbValueMapper.string(row, "detail_description"),
                "symptomType", DbValueMapper.string(row, "symptom_type"),
                "symptom", DbValueMapper.string(row, "symptom"),
                "detectedAt", DbValueMapper.timestamp(row, "detected_at"),
                "incidentWindow", DbValueMapper.json(row, "incident_window", Map.of()),
                "supportRequestKind", DbValueMapper.string(row, "support_request_kind"),
                "status", DbValueMapper.string(row, "status"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private static String titleFor(String symptomType) {
        return switch (symptomType) {
            case "REMOTE_DRIVER_OS" -> "디스플레이 드라이버 경고가 감지되었습니다";
            case "REMOTE_STORAGE_MEMORY" -> "메모리 또는 저장공간 이상이 감지되었습니다";
            case "VISIT_POWER_SHUTDOWN" -> "전원 꺼짐 또는 재부팅 이상이 감지되었습니다";
            default -> "PC Agent가 문제를 감지했습니다";
        };
    }

    private static String detailFor(String symptomType) {
        return switch (symptomType) {
            case "REMOTE_DRIVER_OS" -> "PC Agent가 디스플레이 드라이버 관련 경고 이벤트를 감지했습니다. 선택된 구간의 로그를 함께 전송합니다.";
            case "REMOTE_STORAGE_MEMORY" -> "PC Agent가 메모리 또는 저장공간 압박 징후를 감지했습니다. 선택된 구간의 로그를 함께 전송합니다.";
            case "VISIT_POWER_SHUTDOWN" -> "PC Agent가 전원 안정성 관련 이상 징후를 감지했습니다. 선택된 구간의 로그를 함께 전송합니다.";
            default -> "PC Agent가 문제 이벤트를 감지했습니다. 선택된 구간의 로그를 함께 전송합니다.";
        };
    }

    private Map<String, Object> existingUploadResult(
            AgentPrincipal principal,
            String idempotencyKey,
            String gzipSha256,
            int rangeMinutes,
            int schemaVersion,
            String symptom,
            PcAgentLogAnalyzer.IncidentWindow incidentWindow
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT uj.public_id::text AS upload_job_id,
                       lu.public_id::text AS log_upload_id,
                       t.public_id::text AS ticket_id,
                       t.status,
                       t.analysis_status,
                       t.review_status,
                       t.support_decision,
                       t.risk_level,
                       lu.range_minutes,
                       lu.delete_after,
                       alb.schema_version,
                       alb.sha256,
                       t.symptom,
                       uj.range_started_at,
                       uj.range_ended_at,
                       t.incident_window,
                       t.log_summary,
                       t.support_routing,
                       t.ai_diagnosis_request
                FROM agent_upload_jobs uj
                JOIN agent_log_uploads lu ON lu.upload_job_id = uj.id
                JOIN agent_log_bundles alb ON alb.upload_job_id = uj.id
                JOIN as_tickets t ON t.log_upload_id = lu.id
                WHERE uj.device_id = ?
                  AND uj.idempotency_key = ?
                ORDER BY uj.id DESC
                LIMIT 1
                """, principal.deviceInternalId(), idempotencyKey);
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        boolean sameRequest = rangeMinutes == integer(row, "range_minutes", -1)
                && schemaVersion == integer(row, "schema_version", -1)
                && gzipSha256.equals(DbValueMapper.string(row, "sha256"))
                && symptom.equals(DbValueMapper.string(row, "symptom"))
                && incidentWindow.startedAt().equals(instantValue(row, "range_started_at"))
                && incidentWindow.endedAt().equals(instantValue(row, "range_ended_at"));
        if (!sameRequest) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used with a different upload request.");
        }

        return MockData.map(
                "uploadJobId", DbValueMapper.string(row, "upload_job_id"),
                "logUploadId", DbValueMapper.string(row, "log_upload_id"),
                "ticketId", DbValueMapper.string(row, "ticket_id"),
                "status", DbValueMapper.string(row, "status"),
                "analysisStatus", DbValueMapper.string(row, "analysis_status"),
                "reviewStatus", DbValueMapper.string(row, "review_status"),
                "supportDecision", DbValueMapper.string(row, "support_decision"),
                "riskLevel", DbValueMapper.string(row, "risk_level"),
                "rangeMinutes", rangeMinutes,
                "incidentWindow", DbValueMapper.json(row, "incident_window", incidentWindow.toMap()),
                "supportRouting", DbValueMapper.json(row, "support_routing", Map.of()),
                "rawSamplesCount", rawSamplesCount(DbValueMapper.json(row, "log_summary", Map.of())),
                "deleteAfter", DbValueMapper.timestamp(row, "delete_after")
        );
    }

    private static List<Map<String, Object>> causeCandidatesFrom(Map<String, Object> supportRouting) {
        Object reasonCodesValue = supportRouting.get("reasonCodes");
        if (!(reasonCodesValue instanceof List<?> reasonCodes) || reasonCodes.isEmpty()) {
            return List.of(MockData.map(
                    "code", "INSUFFICIENT_RULE_SIGNAL",
                    "label", "Additional information required",
                    "confidence", "LOW",
                    "evidenceIds", List.of()
            ));
        }
        String confidence = string(supportRouting, "confidence", "MEDIUM");
        return reasonCodes.stream()
                .limit(5)
                .map(reason -> MockData.map(
                        "code", reason.toString(),
                        "label", reason.toString(),
                        "confidence", confidence,
                        "evidenceIds", List.of()
                ))
                .toList();
    }

    private static String riskLevelForRouting(String recommendedDecision, Map<String, Object> supportRouting) {
        if (Set.of("VISIT_REQUIRED", "REPAIR_OR_REPLACE").contains(recommendedDecision)) {
            return "HIGH";
        }
        if ("UNSUPPORTED".equals(recommendedDecision)) {
            return "LOW";
        }
        String confidence = string(supportRouting, "confidence", "MEDIUM");
        return "HIGH".equals(confidence) ? "MEDIUM" : "LOW".equals(confidence) ? "LOW" : "MEDIUM";
    }

    private static int rawSamplesCount(Object logSummary) {
        if (!(logSummary instanceof Map<?, ?> map)) {
            return 0;
        }
        Object rawSamples = map.get("rawSamples");
        return rawSamples instanceof List<?> list ? list.size() : 0;
    }

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static GzipValidation validateGzip(MultipartFile file) {
        byte[] compressed;
        try {
            compressed = file.getBytes();
        } catch (IOException exception) {
            throw fileValidation("Agent log gzip file cannot be read.");
        }
        if (compressed.length == 0) {
            throw fileValidation("Agent log gzip file is empty.");
        }
        if (compressed.length > MAX_GZIP_BYTES) {
            throw fileValidation("Agent log gzip file is too large.");
        }
        long uncompressedBytes = 0L;
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = gzipInputStream.read(buffer)) != -1) {
                uncompressedBytes += read;
                if (uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                    throw fileValidation("Agent log gzip content is too large.");
                }
                uncompressed.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw fileValidation("Agent log upload must contain valid gzip content.");
        }
        if (uncompressedBytes == 0L) {
            throw fileValidation("Agent log gzip content is empty.");
        }
        String contentText = uncompressed.toString(StandardCharsets.UTF_8);
        if (contentText.lines().noneMatch(line -> !line.isBlank())) {
            throw fileValidation("Agent log gzip content must contain at least one log line.");
        }
        return new GzipValidation(compressed.length, uncompressedBytes, sha256Hex(compressed), contentText);
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Rule diagnosis JSON serialization failed.", exception);
        }
    }

    private static void validateUuid(String fieldName, String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest(fieldName + " is invalid.");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available.", exception);
        }
    }

    private static String newAgentToken() {
        byte[] token = new byte[32];
        SECURE_RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static String fileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "agent-log.jsonl.gz";
        }
        String normalized = original.replace("\\", "/");
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1);
        String sanitized = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "agent-log.jsonl.gz" : sanitized;
    }

    private static String string(Map<String, Object> request, String key, String fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        String value = restoreUtf8MultipartText(request.get(key).toString());
        return value.isBlank() ? fallback : value;
    }

    private static String restoreUtf8MultipartText(String value) {
        if (!looksLikeUtf8Mojibake(value)) {
            return value;
        }
        String restored = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return containsHangul(restored) ? restored : value;
    }

    private static boolean looksLikeUtf8Mojibake(String value) {
        return value.indexOf('Ã') >= 0
                || value.indexOf('Â') >= 0
                || value.indexOf('ê') >= 0
                || value.indexOf('ì') >= 0
                || value.indexOf('í') >= 0
                || value.indexOf('ë') >= 0;
    }

    private static boolean containsHangul(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '\uAC00' && character <= '\uD7AF') {
                return true;
            }
        }
        return false;
    }

    private static String requiredString(Map<String, Object> request, String key) {
        String value = string(request, key, null);
        if (value == null) {
            throw badRequest(key + " is required.");
        }
        return value;
    }

    private static boolean requiredBoolean(Map<String, Object> request, String key) {
        if (request == null || request.get(key) == null) {
            throw badRequest(key + " is required.");
        }
        Object value = request.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = value.toString();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw badRequest(key + " must be boolean.");
    }

    private static void validateIdempotencyKey(String fieldName, String value) {
        if (value == null || !IDEMPOTENCY_KEY_PATTERN.matcher(value).matches()) {
            throw badRequest(fieldName + " is invalid.");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiException fileValidation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "FILE_VALIDATION_ERROR", message);
    }

    private static int integer(Map<String, Object> request, String key, int fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        Object value = request.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static Object timestampParameter(Object value) {
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return Timestamp.from(offsetDateTime.toInstant());
        }
        return value;
    }

    private static Instant instantValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return value == null ? null : Instant.parse(value.toString());
    }

    private record ActivationToken(Long activationTokenId, Long userInternalId) {
    }

    private record GzipValidation(long compressedBytes, long uncompressedBytes, String sha256, String contentText) {
    }
}
