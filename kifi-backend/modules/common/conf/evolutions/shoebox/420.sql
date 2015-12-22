# ELIZA

# --- !Ups

ALTER TABLE message_thread DROP COLUMN external_id;


ALTER TABLE message ADD COLUMN keep_id bigint(20) NOT NULL AFTER seq;

ALTER TABLE message DROP CONSTRAINT IF EXISTS message_f_message_thread;
DROP INDEX message_i_thread_id_created_at;
ALTER TABLE message DROP COLUMN IF EXISTS thread_id;

 CREATE INDEX message_i_keep_id ON message(keep_id);
ALTER TABLE message ADD CONSTRAINT message_f_message_thread FOREIGN KEY (keep_id) REFERENCES message_thread(keep_id);

ALTER TABLE message DROP COLUMN thread_ext_id;
ALTER TABLE message DROP COLUMN external_id;

ALTER TABLE user_thread ADD COLUMN keep_id bigint(20) NOT NULL AFTER user_id;
ALTER TABLE non_user_thread ADD COLUMN keep_id bigint(20) NOT NULL AFTER email_address;

# --- !Downs
