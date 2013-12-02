# ELIZA

# --- !Ups

CREATE INDEX message_i_thread_id_created_at ON message (thread_id, created_at);
-- mysql needs this too:
--DROP INDEX message_i_my_thread ON message;

INSERT INTO evolutions (name, description) VALUES ('126.sql', 'add index on thread id and creation time in message table');

# --- !Downs

-- mysql:
--CREATE INDEX message_i_my_thread ON message (thread_id);
--DROP INDEX message_i_thread_id_created_at ON message;

-- h2:
DROP INDEX message_i_thread_id_created_at;
