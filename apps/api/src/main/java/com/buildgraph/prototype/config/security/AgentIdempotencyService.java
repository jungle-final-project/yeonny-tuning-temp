package com.buildgraph.prototype.config.security;

import com.buildgraph.prototype.agent.persistence.AgentIdempotencyRecordEntity;
import com.buildgraph.prototype.agent.persistence.AgentIdempotencyRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentIdempotencyService {
    private static final Duration RECORD_TTL = Duration.ofHours(24);

    private final AgentIdempotencyRecordRepository repository;
    private final Clock clock;

    @Autowired
    public AgentIdempotencyService(AgentIdempotencyRecordRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AgentIdempotencyService(AgentIdempotencyRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AgentIdempotencyDecision reserve(
            AgentPrincipal principal,
            String requestMethod,
            String requestPath,
            String idempotencyKey,
            String requestHash
    ) {
        return repository.findByAgentDeviceIdAndRequestMethodAndRequestPathAndIdempotencyKey(
                        principal.deviceInternalId(),
                        requestMethod,
                        requestPath,
                        idempotencyKey
                )
                .map(record -> decisionForExisting(record, requestHash))
                .orElseGet(() -> createRecord(principal, requestMethod, requestPath, idempotencyKey, requestHash));
    }

    @Transactional
    public void complete(Long recordId, Integer responseStatus, String responseBody, String responseContentType) {
        repository.findById(recordId)
                .ifPresent(record -> record.complete(responseStatus, responseBody, responseContentType, Instant.now(clock)));
    }

    private AgentIdempotencyDecision createRecord(
            AgentPrincipal principal,
            String requestMethod,
            String requestPath,
            String idempotencyKey,
            String requestHash
    ) {
        Instant now = Instant.now(clock);
        AgentIdempotencyRecordEntity record = new AgentIdempotencyRecordEntity(
                principal.deviceInternalId(),
                idempotencyKey,
                requestMethod,
                requestPath,
                requestHash,
                now,
                now.plus(RECORD_TTL)
        );
        try {
            return AgentIdempotencyDecision.proceed(repository.saveAndFlush(record).id());
        } catch (DataIntegrityViolationException exception) {
            return repository.findByAgentDeviceIdAndRequestMethodAndRequestPathAndIdempotencyKey(
                            principal.deviceInternalId(),
                            requestMethod,
                            requestPath,
                            idempotencyKey
                    )
                    .map(existing -> decisionForExisting(existing, requestHash))
                    .orElseThrow(() -> exception);
        }
    }

    private AgentIdempotencyDecision decisionForExisting(AgentIdempotencyRecordEntity record, String requestHash) {
        if (!record.hasRequestHash(requestHash)) {
            return AgentIdempotencyDecision.conflict();
        }
        if (record.isCompleted()) {
            return AgentIdempotencyDecision.replay(
                    record.responseStatus(),
                    record.responseBody(),
                    record.responseContentType()
            );
        }
        return AgentIdempotencyDecision.inProgress();
    }
}
