#SHOEBOX

# --- !Ups

DROP TABLE article_search_result;
insert into evolutions (name, description) values('110.sql', 'drop table article_search_result');

# --- !Downs
