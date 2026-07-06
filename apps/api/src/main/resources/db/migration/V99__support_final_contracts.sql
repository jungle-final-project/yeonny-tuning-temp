ALTER TABLE as_tickets
  DROP CONSTRAINT IF EXISTS chk_as_tickets_support_decision;

ALTER TABLE as_tickets
  ADD CONSTRAINT chk_as_tickets_support_decision CHECK (
    support_decision IS NULL
    OR support_decision IN (
      'SELF_SOLVABLE',
      'REMOTE_POSSIBLE',
      'VISIT_REQUIRED',
      'REPAIR_OR_REPLACE',
      'NEEDS_MORE_INFO',
      'MONITOR_ONLY',
      'UNSUPPORTED'
    )
  );

ALTER TABLE as_tickets
  ADD COLUMN IF NOT EXISTS incident_window JSONB,
  ADD COLUMN IF NOT EXISTS log_summary JSONB,
  ADD COLUMN IF NOT EXISTS support_routing JSONB;
