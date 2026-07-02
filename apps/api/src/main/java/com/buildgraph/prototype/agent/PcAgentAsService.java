package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenHasher;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PcAgentAsService {
    private static final String DEMO_ACTIVATION_TOKEN = "demo-agent-activation-token";
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
    public Map<String, Object> register(Map<String, Object> request) {
        String activationToken = string(request, "activationToken", null);
        if (!DEMO_ACTIVATION_TOKEN.equals(activationToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent activation token is invalid.");
        }

        String rawAgentToken = tokenGenerator.get();
        String tokenHash = tokenHasher.sha256Hex(rawAgentToken);
        String deviceFingerprintHash = string(request, "deviceFingerprintHash", "demo-device-fingerprint");
        String hostnameHash = string(request, "hostnameHash", null);
        String registrationKey = string(request, "registrationIdempotencyKey", "demo-register-" + deviceFingerprintHash);
        String osVersion = string(request, "osVersion", "Windows");
        String agentVersion = string(request, "agentVersion", "0.1.0");
        String policyVersion = string(request, "policyVersion", "demo-policy-v1");
        String userEmail = string(request, "userEmail", null);
        if (userEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent registration userEmail is required.");
        }
        Long userInternalId = userInternalIdByEmail(userEmail);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
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
                  NULL,
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
                deviceFingerprintHash,
                hostnameHash,
                tokenHash,
                registrationKey,
                osVersion,
                agentVersion,
                policyVersion
        );

        return MockData.map(
                "deviceId", DbValueMapper.string(row, "device_id"),
                "status", DbValueMapper.string(row, "status"),
                "agentToken", rawAgentToken,
                "tokenType", "Bearer"
        );
    }

    private Long userInternalIdByEmail(String email) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id
                FROM users
                WHERE email = ?
                  AND deleted_at IS NULL
                """, email);
        return rows.stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent registration user was not found."));
    }

    @Transactional
    public Map<String, Object> saveConsent(
            AgentPrincipal principal,
            Map<String, Object> request,
            String idempotencyKey
    ) {
        boolean accepted = booleanValue(request, "accepted", true);
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
                string(request, "consentType", "SERVER_UPLOAD"),
                string(request, "policyVersion", "demo-policy-v1"),
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
        String agentVersion = string(request, "agentVersion", "0.1.0");
        String policyVersion = string(request, "policyVersion", null);
        jdbcTemplate.update("""
                UPDATE agent_devices
                SET last_seen_at = now(),
                    agent_version = ?,
                    policy_version = COALESCE(?, policy_version),
                    updated_at = now()
                WHERE id = ?
                """, agentVersion, policyVersion, principal.deviceInternalId());

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
                string(request, "serviceStatus", "RUNNING"),
                string(request, "trayStatus", null),
                policyVersion,
                idempotencyKey
        );

        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "deviceId", principal.deviceId(),
                "status", principal.status(),
                "receivedAt", DbValueMapper.timestamp(row, "received_at"),
                "pendingCommands", java.util.List.of()
        );
    }

    @Transactional
    public Map<String, Object> uploadLogs(
            AgentPrincipal principal,
            MultipartFile file,
            Map<String, Object> metadata,
            String idempotencyKey
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent log gzip file is required.");
        }
        String fileName = fileName(file);
        if (!fileName.endsWith(".gz")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent log upload must be gzip.");
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

        int rangeMinutes = integer(metadata, "rangeMinutes", 30);
        Instant rangeEndedAt = instant(metadata, "rangeEndedAt", Instant.now(clock));
        Instant rangeStartedAt = instant(metadata, "rangeStartedAt", rangeEndedAt.minus(Duration.ofMinutes(rangeMinutes)));
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
                rangeStartedAt,
                rangeEndedAt
        );

        Long uploadJobInternalId = longValue(uploadJob, "upload_job_internal_id");
        String storagePath = "agent-logs/" + principal.deviceId() + "/" + fileName;
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
                  consent_accepted_at,
                  delete_after
                )
                VALUES (?, ?, ?, ?, 'UPLOADED', ?, ?, ?, 'Rule demo upload accepted.', now(), now() + interval '30 days')
                RETURNING id AS log_upload_internal_id,
                          public_id::text AS log_upload_id,
                          status,
                          file_name,
                          file_size,
                          range_minutes
                """,
                principal.userInternalId(),
                principal.deviceInternalId(),
                uploadJobInternalId,
                rangeMinutes,
                fileName,
                file.getSize(),
                storagePath
        );

        Long logUploadInternalId = longValue(logUpload, "log_upload_internal_id");
        String symptom = string(metadata, "symptom", "Agent uploaded recent 30 minute diagnostic log.");
        Map<String, Object> ticket = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
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
                  updated_at
                )
                VALUES (
                  ?,
                  ?,
                  ?,
                  'OPEN',
                  'RULE_READY',
                  'REQUIRED',
                  'NEEDS_MORE_INFO',
                  'MEDIUM',
                  false,
                  '[{"label":"Recent agent log uploaded","confidence":"MEDIUM","reason":"Demo rule diagnosis placeholder"}]'::jsonb,
                  '[]'::jsonb,
                  'Rule-based demo diagnosis is ready for admin review.',
                  now()
                )
                RETURNING public_id::text AS ticket_id,
                          status,
                          analysis_status,
                          review_status,
                          support_decision
                """,
                principal.userInternalId(),
                logUploadInternalId,
                symptom
        );

        return MockData.map(
                "uploadJobId", DbValueMapper.string(uploadJob, "upload_job_id"),
                "logUploadId", DbValueMapper.string(logUpload, "log_upload_id"),
                "ticketId", DbValueMapper.string(ticket, "ticket_id"),
                "status", DbValueMapper.string(ticket, "status"),
                "analysisStatus", DbValueMapper.string(ticket, "analysis_status"),
                "reviewStatus", DbValueMapper.string(ticket, "review_status"),
                "supportDecision", DbValueMapper.string(ticket, "support_decision"),
                "rangeMinutes", rangeMinutes
        );
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
        return original.replace("\\", "/").substring(original.replace("\\", "/").lastIndexOf('/') + 1);
    }

    private static String string(Map<String, Object> request, String key, String fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        String value = request.get(key).toString();
        return value.isBlank() ? fallback : value;
    }

    private static boolean booleanValue(Map<String, Object> request, String key, boolean fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        Object value = request.get(key);
        return value instanceof Boolean booleanValue ? booleanValue : Boolean.parseBoolean(value.toString());
    }

    private static int integer(Map<String, Object> request, String key, int fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        Object value = request.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static Instant instant(Map<String, Object> request, String key, Instant fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        return Instant.parse(request.get(key).toString());
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }
}
