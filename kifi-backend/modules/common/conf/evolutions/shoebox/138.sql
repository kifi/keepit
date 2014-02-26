#ABOOK

# --- !Ups

ALTER TABLE econtact ADD COLUMN contact_user_id bigint(20);
CREATE TABLE IF NOT EXISTS rich_social_connection (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    user_social_id bigint(20),
    connection_type varchar(32) NOT NULL,
    friend_social_id bigint(20),
    friend_email_address varchar(512),
    friend_user_id bigint(20),
    friend_name varchar(1024),
    common_kifi_friends_count int NOT NULL,
    kifi_friends_count int NOT NULL,
    invitation bigint(20),
    invitation_count int NOT NULL,
    blocked boolean NOT NULL,
    PRIMARY KEY (id)
);
insert into evolutions (name, description) values('138.sql', 'add contact_user_id to econtact table and create rich_social_connection table');

# --- !Downs
