# ABOOK

# --- !Ups

CREATE TABLE if not exists facebook_invite_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    facebook_account_id bigint(20) NOT NULL,
    irrelevant bool NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX facebook_invite_recommendation_u_user_id_facebook_account_id (user_id, facebook_account_id)
);

CREATE TABLE if not exists linked_in_invite_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    linked_in_account_id bigint(20) NOT NULL,
    irrelevant bool NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX linked_in_invite_recommendation_u_user_id_linked_in_account_id (user_id, linked_in_account_id)
);

CREATE TABLE if not exists email_invite_recommendation (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    email_account_id bigint(20) NOT NULL,
    irrelevant bool NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX email_invite_recommendation_u_user_id_email_account_id (user_id, email_account_id)
);

insert into evolutions (name, description) values('219.sql', 'create facebook_invite_recommendation, linked_in_invite_recommendation and email_invite_recommendation tables');

# --- !Downs
