# SHOEBOX

# --- !Ups

alter TABLE invitation
    add column times_sent int NOT NULL DEFAULT 0;

insert into evolutions (name, description) values('220.sql', 'add sent count to invitation');

# --- !Downs