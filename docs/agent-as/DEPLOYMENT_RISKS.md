# Agent AS Deployment Risk Register

Updated: 2026-07-02

## Purpose

This file records service-deployment risks that must not be hidden inside code comments or chat history. Add new risks here when a change cannot be safely merged, shipped, or operated within the current Agent AS ownership boundaries.

## How to Add a Risk

Use this format and keep facts separate from assumptions.

```md
### RISK-YYYYMMDD-XX: Short title

- Owner: A | B | C | Shared
- Severity: P0 | P1 | P2 | P3
- Status: Open | Mitigated | Deferred | Blocked
- Source: file, branch, test, runtime QA, or meeting note
- Fact:
- Impact:
- Current decision:
- Next action:
```

## Active Risks

### RISK-20260702-B01: Activation token issuing is backend-only

- Owner: B, A
- Severity: P1
- Status: Mitigated for backend, Open for UI/contract
- Source: `apps/api/src/main/java/com/buildgraph/prototype/agent/PcAgentAsService.java`, `apps/api/src/main/java/com/buildgraph/prototype/admin/AdminController.java`
- Fact: Register now validates `agent_activation_tokens.token_hash` and no longer treats the old demo activation token as product behavior. Backend admin API can issue activation tokens and returns the raw token only once.
- Impact: Service deployment can bootstrap Agent registration through backend/API, but A still needs to decide how web UI and OpenAPI contract expose the issue/reissue flow.
- Current decision: Keep the backend issuing path in development. Do not build web UI in B scope.
- Next action: A should document and expose the admin/user-facing activation token issue or reissue UX.

### RISK-20260702-B02: Remote feature branches are stale relative to `pcagent/main`

- Owner: Shared
- Severity: P1
- Status: Deferred
- Source: `pcagent/feat/goal4-5-agent-lifecycle`, `pcagent/feat/goal6-7-support-flow`, `pcagent/feat/goal8-agent-as-e2e-demo`, `pcagent/fix-integration-goal6-7-hardening`
- Fact: These branches are not ahead of `pcagent/main` after fetch. Their content diff against current main includes broad deletions of `apps/pc-agent`, web assets, QA artifacts, and docs.
- Impact: Merging them now would collide with A/C ownership and may remove already-merged runtime QA, exe download, and visual QA artifacts.
- Current decision: Do not merge these remote branches into the B development changes. Treat them as stale or archival unless a teammate rebases them onto `pcagent/main`.
- Next action: Each owner should rebase or recreate their branch from `pcagent/main = c2b1976` before requesting integration.

### RISK-20260702-B03: Async diagnosis queue is model-ready, not worker-ready

- Owner: B
- Severity: P2
- Status: Deferred
- Source: `as_tickets.analysis_status`, Agent upload service tests
- Fact: Current implementation stores rule-based diagnosis synchronously as `RULE_READY`. Existing enum has queue-oriented states such as `QUEUED` and `ANALYZING`, but no durable worker or queue consumer is wired for diagnosis jobs.
- Impact: Service can deploy with deterministic rule diagnosis, but it must not be described as an actual AI or queue-based diagnosis pipeline.
- Current decision: Keep internal rule diagnosis in development. Do not add external AI provider integration or queue worker in B scope.
- Next action: Add a dedicated worker design only after queue ownership, retry policy, and operational monitoring are assigned.

### RISK-20260702-B04: Runtime QA requires real activation token data

- Owner: B
- Severity: P1
- Status: Open
- Source: Agent register hardening
- Fact: Runtime happy path can no longer use `demo-agent-activation-token` unless a matching `agent_activation_tokens.token_hash` row exists.
- Impact: Existing PC Agent config examples and old QA scripts may fail at register with `401` until they first call the backend activation token issuing API or seed a valid token.
- Current decision: Keep this stricter behavior because it matches service deployment security.
- Next action: Update QA runbook and A/C handoff after A decides the user-facing activation token delivery path.

### RISK-20260702-B05: Full runtime QA is blocked by local database credentials

- Owner: B
- Severity: P2
- Status: Open
- Source: local verification
- Fact: Gradle tests, bootJar, OpenAPI validation, and whitespace checks passed. Runtime API boot reached Flyway, then failed before health check because the local PostgreSQL instance on `localhost:5432` rejects the repo default `buildgraph` credentials.
- Impact: Static and automated API tests cover the server behavior, but register to upload to admin decision to user lookup was not completed against the current local DB.
- Current decision: Keep code in development with test coverage. Do not reset or recreate the existing local database volume in B scope.
- Next action: Rerun runtime QA in a clean container or shell with known database credentials, using a freshly issued activation token.
