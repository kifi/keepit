# SHOEBOX

# --- !Ups

alter table library_invite
    modify column passcode varchar(30) not null;

insert into evolutions (name, description) values('247.sql', 'extend passcode column');

# --- !Downs
