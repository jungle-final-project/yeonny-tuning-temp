package com.buildgraph.prototype.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.persistence.AgentIdempotencyRecordEntity;
import com.buildgraph.prototype.agent.persistence.AgentIdempotencyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AgentIdempotencyServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentPrincipal AGENT = new AgentPrincipal(10L, "device-a", 20L, "ACTIVE");

    private final AgentIdempotencyRecordRepository repository = org.mockito.Mockito.mock(AgentIdempotencyRecordRepository.class);
    private final AgentIdempotencyService service = new AgentIdempotencyService(repository, CLOCK);

    @Test
    void reserveCreatesRecordScopedByAgentMethodPathAndKey() {
        when(repository.findByAgentDeviceIdAndRequestMethodAndRequestPathAndIdempotencyKey(
                10L, "POST", "/api/agent/mutations", "same-key"
        )).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AgentIdempotencyRecordEntity.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 100L));

        AgentIdempotencyDecision decision = service.reserve(
                AGENT,
                "POST",
                "/api/agent/mutations",
                "same-key",
                "hash-a"
        );

        assertThat(decision.status()).isEqualTo(AgentIdempotencyDecision.Status.PROCEED);
        assertThat(decision.recordId()).isEqualTo(100L);

        ArgumentCaptor<AgentIdempotencyRecordEntity> captor = ArgumentCaptor.forClass(AgentIdempotencyRecordEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        AgentIdempotencyRecordEntity record = captor.getValue();
        assertThat(record.agentDeviceId()).isEqualTo(10L);
        assertThat(record.idempotencyKey()).isEqualTo("same-key");
        assertThat(record.requestMethod()).isEqualTo("POST");
        assertThat(record.requestPath()).isEqualTo("/api/agent/mutations");
        assertThat(record.hasRequestHash("hash-a")).isTrue();
    }

    @Test
    void reserveReplaysCompletedRecordForSameHash() {
        AgentIdempotencyRecordEntity record = startedRecord("hash-a");
        record.complete(201, "{\"ok\":true}", "application/json", Instant.now(CLOCK));

        when(repository.findByAgentDeviceIdAndRequestMethodAndRequestPathAndIdempotencyKey(
                10L, "POST", "/api/agent/mutations", "same-key"
        )).thenReturn(Optional.of(record));

        AgentIdempotencyDecision decision = service.reserve(
                AGENT,
                "POST",
                "/api/agent/mutations",
                "same-key",
                "hash-a"
        );

        assertThat(decision.status()).isEqualTo(AgentIdempotencyDecision.Status.REPLAY);
        assertThat(decision.responseStatus()).isEqualTo(201);
        assertThat(decision.responseBody()).isEqualTo("{\"ok\":true}");
        assertThat(decision.responseContentType()).isEqualTo("application/json");
    }

    @Test
    void reserveRejectsSameKeyWithDifferentHashAsConflict() {
        when(repository.findByAgentDeviceIdAndRequestMethodAndRequestPathAndIdempotencyKey(
                10L, "POST", "/api/agent/mutations", "same-key"
        )).thenReturn(Optional.of(startedRecord("hash-a")));

        AgentIdempotencyDecision decision = service.reserve(
                AGENT,
                "POST",
                "/api/agent/mutations",
                "same-key",
                "hash-b"
        );

        assertThat(decision.status()).isEqualTo(AgentIdempotencyDecision.Status.CONFLICT);
    }

    @Test
    void reserveKeepsDifferentAgentScopeIndependentForSameKey() {
        when(repository.findByAgentDeviceIdAndRequestMethodAndRequestPathAndIdempotencyKey(
                10L, "POST", "/api/agent/mutations", "same-key"
        )).thenReturn(Optional.empty());
        when(repository.findByAgentDeviceIdAndRequestMethodAndRequestPathAndIdempotencyKey(
                11L, "POST", "/api/agent/mutations", "same-key"
        )).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AgentIdempotencyRecordEntity.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 101L));

        AgentIdempotencyDecision first = service.reserve(
                AGENT,
                "POST",
                "/api/agent/mutations",
                "same-key",
                "hash-a"
        );
        AgentIdempotencyDecision second = service.reserve(
                new AgentPrincipal(11L, "device-b", 21L, "ACTIVE"),
                "POST",
                "/api/agent/mutations",
                "same-key",
                "hash-b"
        );

        assertThat(first.status()).isEqualTo(AgentIdempotencyDecision.Status.PROCEED);
        assertThat(second.status()).isEqualTo(AgentIdempotencyDecision.Status.PROCEED);
    }

    private static AgentIdempotencyRecordEntity startedRecord(String requestHash) {
        return new AgentIdempotencyRecordEntity(
                10L,
                "same-key",
                "POST",
                "/api/agent/mutations",
                requestHash,
                Instant.now(CLOCK),
                Instant.now(CLOCK).plusSeconds(86_400)
        );
    }

    private static AgentIdempotencyRecordEntity withId(AgentIdempotencyRecordEntity record, Long id) {
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }
}
