# SHOEBOX

# --- !Ups

ALTER TABLE keep_tag ADD INDEX keep_tag_i_normalized_tag (normalized_tag);

insert into evolutions (name, description) values('442.sql', 'add index to keep_tag.normalized_tag');

# --- !Downs
