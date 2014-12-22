# SHOEBOX

# --- !Ups

DROP INDEX library_membership_i_visible ON library_membership;
ALTER TABLE library_membership DROP COLUMN visibility;

CREATE INDEX library_membership_i_listed ON library_membership (listed);

insert into evolutions (name, description) values('278.sql', 'drop visibility column from library membership');

# --- !Downs
