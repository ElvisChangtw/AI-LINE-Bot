-- Voice and video length, so long clips are declined at once and transcription spend can be estimated.
ALTER TABLE material ADD COLUMN media_duration_ms INTEGER;
