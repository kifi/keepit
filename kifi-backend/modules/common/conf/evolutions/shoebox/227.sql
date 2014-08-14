# CURATOR

# --- !Ups

alter table uri_recommendation
    change seen delivered bigint(20);

alter table uri_recommendation
    modify clicked bigint(20);

alter table uri_recommendation
    add column deleted boolean NOT NULL DEFAULT false;

alter table uri_recommendation
    add column markedBad boolean NOT NULL DEFAULT false;

insert into evolutions (name, description) values('227.sql', 'add attribution column');

# --- !Downs
