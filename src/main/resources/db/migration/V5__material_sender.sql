-- Who sent a material, hashed; lets a look-back join one person's consecutive messages.
ALTER TABLE material ADD COLUMN sender_hash TEXT;
