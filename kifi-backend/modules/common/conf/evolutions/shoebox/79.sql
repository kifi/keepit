# --- !Ups

alter TABLE normalized_uri
    add column normalization varchar(32) NULL ;

insert into evolutions (name, description) values('79.sql', 'add normalization to normalized_uri');

# --- !Downs
