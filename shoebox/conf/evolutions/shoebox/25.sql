# --- !Ups

alter TABLE normalized_uri
  add column metadata varchar(1024);
alter TABLE bookmark
  add column metadata varchar(1024);
alter TABLE comment
  add column metadata varchar(1024);
alter TABLE deep_link
  add column metadata varchar(1024);
alter TABLE follow
  add column metadata varchar(1024);
alter TABLE scrape_info
  add column metadata varchar(1024);

insert into evolutions (name, description) values('25.sql', 'adding metadata (JSON) to all tables that use a normalized_uri_id');

# --- !Downs
