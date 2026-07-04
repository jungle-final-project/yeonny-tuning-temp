CREATE TABLE IF NOT EXISTS build_chat_semantic_cache (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    profile VARCHAR(80) NOT NULL,
    intent VARCHAR(80) NOT NULL,
    constraint_signature TEXT NOT NULL,
    message TEXT NOT NULL,
    embedding VECTOR(1536) NOT NULL,
    response_payload JSONB NOT NULL,
    data_version_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_hit_at TIMESTAMPTZ,
    hit_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_build_chat_semantic_cache_lookup
ON build_chat_semantic_cache (profile, intent, constraint_signature, data_version_hash, expires_at);

CREATE INDEX IF NOT EXISTS idx_build_chat_semantic_cache_embedding_hnsw
ON build_chat_semantic_cache
USING hnsw (embedding vector_cosine_ops);
