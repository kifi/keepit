# SHOEBOX

# --- !Ups

ALTER TABLE library_membership DROP COLUMN last_joined_at;
ALTER TABLE library_membership ADD INDEX library_membership_i_created_at (created_at);

insert into evolutions (name, description) values('433.sql', 'drop last_joined_at from library_membership');

# --- !Downs
