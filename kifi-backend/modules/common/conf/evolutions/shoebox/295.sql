# ABOOK

# --- !Ups

CREATE TABLE if not exists twitter_invite_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    twitter_account_id bigint(20) NOT NULL,
    irrelevant bool NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX twitter_invite_recommendation_u_user_id_twitter_account_id (user_id, twitter_account_id)
);

insert into evolutions (name, description) values('295.sql', 'creates twitter_invite_recommendation table');
