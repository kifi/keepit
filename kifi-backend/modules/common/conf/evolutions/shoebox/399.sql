# SHOEBOX

# --- !Ups

ALTER TABLE bookmark MODIFY COLUMN libraries_hash int(10) NOT NULL;
ALTER TABLE bookmark MODIFY COLUMN participants_hash int(10) NOT NULL;

insert into evolutions(name, description) values('399.sql', 'made keep hashes (libraries, participants) not null');

# --- !Downs
