# SHOEBOX

# --- !Ups

ALTER TABLE library_membership MODIFY COLUMN visibility varchar(20) DEFAULT 'visible';
ALTER TABLE library_membership ADD COLUMN listed tinyint(1) NOT NULL DEFAULT 1;

insert into evolutions (name, description) values('276.sql', 'add listed column to the library membership');

# --- !Downs
