# --- !Ups

CREATE TABLE article_search_result (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    user_id bigint(20) NOT NULL,
    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),
    
    UNIQUE INDEX(external_id),
    CREATE INDEX article_search_result_i_created_at(created_at),
    
    CONSTRAINT article_search_result_f_user FOREIGN KEY (user_id) REFERENCES user(id) 
);

insert into evolutions (name, description) values('8.sql', 'adding ArticleSearchResult table');

# --- !Downs
