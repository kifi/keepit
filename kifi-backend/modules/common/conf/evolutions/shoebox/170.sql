# ELIZA

# --- !Ups

CREATE INDEX non_user_thread_i_created_by_created_at ON non_user_thread (created_by, created_at);

INSERT INTO evolutions (name, description) VALUES ('170.sql', 'add index on creator and creation time in non_user_thread table');

# --- !Downs