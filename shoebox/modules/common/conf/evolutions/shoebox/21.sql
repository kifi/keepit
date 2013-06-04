# --- !Ups

alter TABLE bookmark
    add column kifi_installation varchar(36);
    
insert into evolutions (name, description) values('21.sql', 'adding kifi_installation column to bookmark table');

# --- !Downs
