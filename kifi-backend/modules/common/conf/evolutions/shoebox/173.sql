# ELIZA

# --- !Ups

CREATE INDEX non_user_thread_i_last_notified_at_thread_updated_at ON non_user_thread (last_notified_at, thread_updated_at);
-- DROP INDEX non_user_thread_i_last_notified_at ON non_user_thread

INSERT INTO evolutions (name, description) VALUES ('173.sql', 'add index on last notified at and thread updated at in non_user_thread table');

# --- !Downs
