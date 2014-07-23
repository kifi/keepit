# SHOEBOX

# --- !Ups

ALTER TABLE user ADD COLUMN normalized_username varchar(64) NOT NULL;

ALTER TABLE user ADD INDEX user_i_normalized_username (normalized_username);

ALTER TABLE library_membership ADD COLUMN show_in_search boolean NOT NULL DEFAULT true;

ALTER TABLE library ADD COLUMN is_searchable_by_others boolean NOT NULL DEFAULT true;

insert into evolutions (name, description) values('204.sql', 'add normalized username column, show_in_search for library_membership');

# --- !Downs
