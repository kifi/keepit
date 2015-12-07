# ELIZA

# --- !Ups

ALTER TABLE message_thread ADD COLUMN started_by BIGINT(20) NOT NULL AFTER url;

# --- !Downs
