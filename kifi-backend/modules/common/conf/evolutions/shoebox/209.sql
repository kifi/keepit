# ABOOK

# --- !Ups

CREATE TABLE friend_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    friend_id bigint(20) NOT NULL,
    irrelevant bool NOT NULL,

    PRIMARY KEY (id),
    INDEX friend_recommendation_i_user_id (user_id)
);

insert into evolutions (name, description) values('209.sql', 'create friend_recommendation table');
