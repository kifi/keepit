# ELIZA

# --- !Ups

ALTER TABLE message DROP COLUMN external_id;
ALTER TABLE message DROP COLUMN thread_ext_id;
ALTER TABLE message_thread DROP COLUMN external_id;

ALTER TABLE message DROP CONSTRAINT IF EXISTS message_f_message_thread;
DROP INDEX message_i_thread_id_created_at;
ALTER TABLE message ADD COLUMN keep_id bigint(20) NOT NULL AFTER seq;
 CREATE INDEX message_i_keep_id ON message(keep_id);
ALTER TABLE message ADD CONSTRAINT message_f_message_thread FOREIGN KEY (keep_id) REFERENCES message_thread(keep_id);
ALTER TABLE message DROP COLUMN IF EXISTS thread_id;

ALTER TABLE user_thread DROP CONSTRAINT IF EXISTS user_thread_f_message_thread;
ALTER TABLE user_thread DROP CONSTRAINT IF EXISTS user_thread_i_user_thread;
ALTER TABLE user_thread ADD COLUMN keep_id bigint(20) NOT NULL AFTER user_id;
 CREATE UNIQUE INDEX user_thread_u_user_id_keep_id ON user_thread(user_id, keep_id);
ALTER TABLE user_thread ADD CONSTRAINT user_thread_f_message_thread FOREIGN KEY (keep_id) REFERENCES message_thread(keep_id);
ALTER TABLE user_thread DROP COLUMN IF EXISTS thread_id;

ALTER TABLE non_user_thread DROP CONSTRAINT IF EXISTS non_user_thread_f_message_thread;
ALTER TABLE non_user_thread ADD COLUMN keep_id bigint(20) NOT NULL AFTER email_address;
ALTER TABLE non_user_thread ADD CONSTRAINT non_user_thread_f_message_thread FOREIGN KEY (keep_id) REFERENCES message_thread(keep_id);
ALTER TABLE non_user_thread DROP COLUMN IF EXISTS thread_id;

INSERT INTO evolutions (name, description) VALUES ('421.sql', 'switch {message, user_thread, non_user_thread} from thread_id to keep_id, drop {message, thread}.external_id');

# --- !Downs
