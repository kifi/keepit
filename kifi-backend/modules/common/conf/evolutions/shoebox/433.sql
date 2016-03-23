# SHOEBOX

# --- !Ups

ALTER TABLE library_membership DROP COLUMN last_joined_at;

insert into evolutions (name, description) values('433.sql', 'drop last_joined_at from library_membership');

# --- !Downs
