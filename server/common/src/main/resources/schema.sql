-- Concord schema, exactly as docs/SPEC.md §5.1. Applied by `Migrate` (common), which
-- records it in schema_migrations so re-running is a no-op.

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,         -- scrypt or argon2id
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE boards (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    version BIGINT NOT NULL DEFAULT 0,   -- last committed op version
    fence BIGINT NOT NULL DEFAULT 0,     -- highest owner fence seen
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE board_members (
    board_id UUID REFERENCES boards(id),
    user_id UUID REFERENCES users(id),
    role TEXT NOT NULL CHECK (role IN ('owner','editor','viewer')),
    PRIMARY KEY (board_id, user_id)
);

-- Not partitioned in v1. If volume ever needs it: PARTITION BY HASH (board_id),
-- which keeps both keys legal because both include board_id.
CREATE TABLE board_ops (
    board_id UUID NOT NULL REFERENCES boards(id),
    op_id UUID NOT NULL,
    version BIGINT NOT NULL,
    type TEXT NOT NULL,
    payload JSONB NOT NULL,
    hlc_physical_ms BIGINT NOT NULL,
    hlc_counter INT NOT NULL,
    replica_id UUID NOT NULL,
    user_id UUID NOT NULL,
    fence BIGINT NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, op_id),
    UNIQUE (board_id, version)
);
CREATE INDEX board_ops_history ON board_ops (board_id, committed_at DESC);

CREATE TABLE board_snapshots (
    board_id UUID NOT NULL REFERENCES boards(id),
    version BIGINT NOT NULL,             -- last op version included
    state JSONB NOT NULL,
    sha256 TEXT NOT NULL,                -- of the canonical encoding (§9.2.2); checked on load
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, version)
);
