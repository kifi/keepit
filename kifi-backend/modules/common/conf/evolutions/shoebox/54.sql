# --- !Ups

alter TABLE invitation
    add column external_id varchar(64);
    
insert into evolutions (name, description) values('54.sql', 'adding externalId to invitation');

# --- !Downs
