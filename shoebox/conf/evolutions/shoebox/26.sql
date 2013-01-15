# --- !Ups

alter TABLE scrape_info
  add column destination_url varchar(2048);

insert into evolutions (name, description) values('26.sql', 'adding column destination_url to scrape_info');

# --- !Downs
