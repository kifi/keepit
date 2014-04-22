# ELIZA

# --- !Ups

CREATE INDEX device_i_token ON device (token);

insert into evolutions (name, description) values('156.sql', 'add token index to device');

# --- !Downs
