# --- !Ups

alter TABLE article_search_result
    create column last varchar(36) NOT NULL;
    
alter TABLE article_search_result
    create column my_total INTEGER NOT NULL;

alter TABLE article_search_result
    create column friends_total INTEGER NOT NULL;

alter TABLE article_search_result
    create column may_have_more_hits BOOLEAN NOT NULL;

alter TABLE article_search_result
    create column millis_passed INTEGER NOT NULL;

alter TABLE article_search_result
    create column hit_count INTEGER NOT NULL;

alter TABLE article_search_result
    create column page_number INTEGER NOT NULL;
    
insert into evolutions (name, description) values('9.sql', 'adding more columns to the article search result table');

# --- !Downs
