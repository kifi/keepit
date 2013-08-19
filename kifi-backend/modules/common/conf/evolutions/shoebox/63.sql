# SHOEBOX

# --- !Ups

CREATE TABLE user_topic (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    topic BLOB NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT user_topic_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    UNIQUE INDEX user_topic_i_user_id(user_id)
);

insert into evolutions (name, description) values('63.sql', 'adding user_topic table');

# --- !Downs