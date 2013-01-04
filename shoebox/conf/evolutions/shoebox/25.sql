# --- !Ups

alter TABLE normalized_uri
  add column uri_data varchar(1024);
alter TABLE bookmark
  add column uri_data varchar(1024);
alter TABLE comment
  add column uri_data varchar(1024);
alter TABLE deep_link
  add column uri_data varchar(1024);
alter TABLE follow
  add column uri_data varchar(1024);
alter TABLE scrape_info
  add column uri_data varchar(1024);

insert into evolutions (name, description) values('25.sql', 'adding uri_data (JSON) to all tables that use a normalized_uri_id');

# --- !Downs
