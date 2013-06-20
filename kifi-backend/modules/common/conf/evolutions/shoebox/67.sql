# --- !Ups

CREATE TABLE topic_seq_num_info (
    id bigint(20) NOT NULL AUTO_INCREMENT,    
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    uri_seq INT NOT NULL,
    bookmark_seq INT NOT NULL,
    PRIMARY KEY (id)
);

insert into evolutions (name, description) values('67.sql', 'adding topic_seq_num_info table');

# --- !Downs