# ELIZA

# --- !Ups

ALTER TABLE message ADD COLUMN keep_id bigint(20) NOT NULL AFTER seq;
CREATE INDEX message_i_keep_id ON message(keep_id);

# --- !Downs
