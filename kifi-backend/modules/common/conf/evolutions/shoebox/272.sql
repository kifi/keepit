# SHOEBOX

# --- !Ups

ALTER TABLE library_membership ADD COLUMN visibility varchar(20) NOT NULL DEFAULT 'visible';
ALTER TABLE library_membership ADD INDEX library_membership_i_visible(visibility);

insert into evolutions (name, description) values('272.sql', 'add visibility column to the library membership');

# --- !Downs
