# CURATOR

# --- !Ups

alter table uri_recommendation
    change seen delivered bigint(20) NOT NULL DEFAULT 0;

alter table uri_recommendation
    modify COLUMN clicked bigint(20) NOT NULL DEFAULT 0;

alter table uri_recommendation
    add column deleted boolean NOT NULL DEFAULT false;

alter table uri_recommendation
    add column markedBad boolean NOT NULL DEFAULT false;

insert into evolutions (name, description) values('227.sql', 'change uri_recommendation column names: seen->delivered, modify clicked column type: boolean -> bigint, adding new columns: deleted, markedBad');

# --- !Downs
