# ELIZA

# --- !Ups

ALTER TABLE message_thread ADD COLUMN started_by BIGINT(20) NOT NULL AFTER url;

ALTER TABLE user_thread ADD COLUMN started_by BIGINT(20) NOT NULL AFTER started;
ALTER TABLE user_thread DROP COLUMN started;

ALTER TABLE user_thread DROP COLUMN last_notification;

# --- !Downs
