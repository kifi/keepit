# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD COLUMN original_keeper_id bigint(20) NULL;

alter table bookmark drop column bookmark_path;

insert into evolutions (name, description) values('326.sql', 'original_keeper_id added to bookmark table');

# --- !Downs
