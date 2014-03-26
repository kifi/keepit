#Shoebox

# --- !Ups
alter table kifi_installation add column platform varchar(64) not null default 'extension';

insert into evolutions (name, description) values('153.sql', 'add platform to the kifi installation table');

# --- !Downs