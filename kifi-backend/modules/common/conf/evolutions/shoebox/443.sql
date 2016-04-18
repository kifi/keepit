# SHOEBOX

# --- !Ups

ALTER TABLE keep_tag ADD INDEX keep_tag_f_message_id (message_id);

ALTER TABLE keep_tag ADD INDEX keep_tag_i_user_id_normalized_tag (user_id, normalized_tag);

insert into evolutions (name, description) values('443.sql', 'Adding indexes to keep_tag');

# --- !Downs
