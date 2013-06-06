# --- !Ups

alter TABLE article_search_result
    add column last varchar(36);
    
alter TABLE article_search_result
    add column my_total INTEGER NOT NULL;

alter TABLE article_search_result
    add column friends_total INTEGER NOT NULL;

alter TABLE article_search_result
    add column may_have_more_hits BOOLEAN NOT NULL;

alter TABLE article_search_result
    add column millis_passed INTEGER NOT NULL;

alter TABLE article_search_result
    add column hit_count INTEGER NOT NULL;

alter TABLE article_search_result
    add column page_number INTEGER NOT NULL;
    
insert into evolutions (name, description) values('9.sql', 'adding more columns to the article search result table');

# --- !Downs
