# ELIZA

# --- !Ups

ALTER TABLE message ADD COLUMN keep_id bigint(20) NOT NULL AFTER seq;
 CREATE INDEX message_i_keep_id ON message(keep_id);
ALTER TABLE message ADD CONSTRAINT message_f_message_thread FOREIGN KEY (keep_id) REFERENCES message_thread(keep_id);

ALTER TABLE message DROP COLUMN thread_ext_id;

# --- !Downs
