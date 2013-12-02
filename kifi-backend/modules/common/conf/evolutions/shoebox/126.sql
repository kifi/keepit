# ELIZA

# --- !Ups

CREATE INDEX message_i_thread_id_created_at ON message (thread_id, created_at);

INSERT INTO evolutions (name, description) VALUES ('126.sql', 'add index on thread id and creation time in message table');

# --- !Downs

DROP INDEX message_i_thread_id_created_at ON message;
