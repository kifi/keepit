# CURATOR

# --- !Ups

alter table uri_recommendation
    add column attribution text NOT NULL;

insert into evolutions (name, description) values('225.sql', 'add attribution column');

# --- !Downs
