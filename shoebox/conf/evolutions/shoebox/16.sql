# --- !Ups

alter table normalized_uri
  alter column normalized_uri normalized_uri varchar(2048);

insert into evolutions (name, description) values('16.sql', 'title may be null in normalized_uri table');

# --- !Downs
