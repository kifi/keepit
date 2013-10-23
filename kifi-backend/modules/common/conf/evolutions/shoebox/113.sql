#SHOEBOX

# --- !Ups

DROP TABLE click_history ;
DROP TABLE browsing_history ;
insert into evolutions (name, description) values('113.sql', 'drop tables click_history and browsing_history');

# --- !Downs
