-- Names need the raw LINE userId (a hash cannot be looked up); it expires with the material like the rest.
ALTER TABLE material ADD COLUMN sender_id TEXT;
ALTER TABLE material DROP COLUMN sender_hash;
