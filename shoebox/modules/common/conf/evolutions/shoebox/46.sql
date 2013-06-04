# --- !Ups

DROP TABLE slider_history;

insert into evolutions (name, description) values('46.sql', 'dropping slider_history table');

# --- !Downs
