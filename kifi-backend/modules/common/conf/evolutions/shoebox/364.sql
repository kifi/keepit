# SHOEBOX

# --- !Ups

ALTER TABLE password_reset MODIFY COLUMN sent_to varchar(512) NOT NULL;

insert into evolutions(name, description) values('364.sql', 'modify password_reset.sent_to to varchar(512) NOT NULL');

# --- !Downs
