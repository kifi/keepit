# ABOOK

# --- !Ups

CREATE TABLE if not exists friend_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    friend_id bigint(20) NOT NULL,
    irrelevant bool NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX friend_recommendation_u_user_id_friend_id (user_id, friend_id)
);

insert into evolutions (name, description) values('210.sql', 'create friend_recommendation table');

# --- !Downs
