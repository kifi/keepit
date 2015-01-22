# SHOEBOX

# --- !Ups

ALTER TABLE library_membership ADD COLUMN last_joined_at DATETIME NULL;

insert into evolutions (name, description) values('285.sql', 'add last_joined_at to library_membership');

# --- !Downs
