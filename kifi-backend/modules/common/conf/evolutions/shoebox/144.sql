# ABOOK

# --- !Ups

CREATE INDEX user_friend_social_i_rich_social_connection ON rich_social_connection(user_id, friend_social_id);

CREATE INDEX user_connt_email_i_rich_social_connection ON rich_social_connection(user_id, connection_type, friend_email_address);

CREATE INDEX user_friend_user_i_rich_social_connection ON rich_social_connection(user_id, friend_user_id);

CREATE INDEX friend_social_i_rich_social_connection ON rich_social_connection(friend_social_id);

CREATE INDEX connt_email_i_rich_social_connection ON rich_social_connection(connection_type, friend_email_address);

insert into evolutions (name, description) values('144.sql', 'adding a bunch of indices to rich_social_connection');

# --- !Downs
