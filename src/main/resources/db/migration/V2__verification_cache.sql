CREATE TABLE verification_cache (
  content_hash TEXT PRIMARY KEY,
  result_json  TEXT    NOT NULL,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL
);
CREATE INDEX verification_cache_expiry ON verification_cache(expires_at);
