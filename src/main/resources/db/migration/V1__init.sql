CREATE TABLE inbound_event (
  webhook_event_id TEXT PRIMARY KEY,
  received_at      INTEGER NOT NULL,
  is_redelivery    INTEGER NOT NULL
);

CREATE TABLE material (
  group_id       TEXT    NOT NULL,
  message_id     TEXT    NOT NULL,
  kind           TEXT    NOT NULL,
  text           TEXT,
  is_bot_trigger INTEGER NOT NULL DEFAULT 0,
  media_status   TEXT    NOT NULL DEFAULT 'NONE',
  media_sha256   TEXT,
  media_attempts INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,
  expires_at     INTEGER NOT NULL,
  PRIMARY KEY (group_id, message_id)
);
CREATE INDEX material_recent ON material(group_id, created_at);
CREATE INDEX material_expiry ON material(expires_at);

CREATE TABLE job (
  id                  TEXT PRIMARY KEY,
  kind                TEXT    NOT NULL,
  group_id            TEXT    NOT NULL,
  trigger_message_id  TEXT,
  material_message_id TEXT,
  user_question       TEXT,
  reply_token         TEXT,
  quote_token         TEXT,
  received_at         INTEGER NOT NULL,
  status              TEXT    NOT NULL,
  attempts            INTEGER NOT NULL DEFAULT 0,
  lease_until         INTEGER,
  result_text         TEXT,
  send_state          TEXT    NOT NULL DEFAULT 'NOT_SENT',
  carrier_job_id      TEXT,
  waiting_since       INTEGER,
  verdict             TEXT,
  error_code          TEXT,
  input_tokens        INTEGER,
  output_tokens       INTEGER,
  search_calls        INTEGER,
  cost_micro_usd      INTEGER,
  created_at          INTEGER NOT NULL,
  finished_at         INTEGER
);
CREATE INDEX job_status ON job(status, created_at);
CREATE INDEX job_group_send ON job(group_id, send_state, waiting_since);
CREATE INDEX job_carrier ON job(carrier_job_id);
