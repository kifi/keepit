# ELIZA

# --- !Ups

alter table device
    modify column token varchar(64) DEFAULT NULL;

insert into evolutions (name, description) values('312.sql', 'alter eliza device repo, make token nullable');

# --- !Downs
