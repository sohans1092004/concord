-- Version 1 -> 2 (Phase 3, amendment 14): names, sign-in sessions, WebSocket tickets, bug
-- reports. A fresh database gets all of this from schema.sql instead.
ALTER TABLE users ADD COLUMN name TEXT NOT NULL DEFAULT '';
ALTER TABLE users ALTER COLUMN name DROP DEFAULT;
UPDATE users SET email = lower(email);

CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user ON sessions (user_id);

CREATE TABLE ws_tickets (
    token_hash TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE bug_reports (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    board_id UUID REFERENCES boards(id),
    description TEXT NOT NULL,
    diagnostics JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
