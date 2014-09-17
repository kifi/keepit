# SHOEBOX

# --- !Ups

alter table library_invite
    add column message text default null;

insert into evolutions (name, description) values('248.sql', 'add message column');

# --- !Downs
