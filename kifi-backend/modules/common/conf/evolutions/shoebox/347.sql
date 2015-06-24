# SHOEBOX

# --- !Ups

DROP INDEX library_id ON library_subscription;

insert into evolutions (name, description) values('347.sql', 'removing constraint for unique library subscription name');

# --- !Downs
