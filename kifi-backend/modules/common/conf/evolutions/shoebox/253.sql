# SHOEBOX

# --- !Ups

ALTER TABLE library
    ADD COLUMN last_kept datetime DEFAULT NULL;

ALTER TABLE library_membership
    ADD COLUMN last_viewed datetime DEFAULT NULL;

insert into evolutions (name, description) values('253.sql', 'last_viewed & last_kept for libraries');

# --- !Downs
