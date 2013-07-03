# --- !Ups

CREATE TABLE topic_name_a (
    id bigint(20) NOT NULL AUTO_INCREMENT,    
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    topic_name varchar(64) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE topic_name_b (
    id bigint(20) NOT NULL AUTO_INCREMENT,    
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    topic_name varchar(64) NOT NULL,
    PRIMARY KEY (id)
);

insert into evolutions (name, description) values('72.sql', 'adding topic_name_a and topic_name_b tables');

# --- !Downs
