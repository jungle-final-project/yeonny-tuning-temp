-- Price policy evidence is used by both build explanation and build recommendation.
-- Keep the primary purpose for existing explain flows, and add a shared purposes list
-- so purpose-filtered retrieval can include it without duplicating the chunk.

UPDATE rag_evidence
SET metadata = jsonb_set(
        coalesce(metadata, '{}'::jsonb),
        '{purposes}',
        '["BUILD_EXPLAIN","BUILD_RECOMMEND"]'::jsonb,
        true
    )
WHERE agent_session_id IS NULL
  AND source_id = 'price-guide-saved-snapshot-first';
