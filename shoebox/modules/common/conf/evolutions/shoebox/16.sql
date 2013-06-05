# --- !Ups

alter table normalized_uri
  alter column title varchar(2048);

insert into evolutions (name, description) values('16.sql', 'title may be null in normalized_uri table');

# --- !Downs
