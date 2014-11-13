#Curator

# --- !Ups

ALTER TABLE uri_recommendation ADD INDEX uri_recommendation_i_user_id_uri_id (user_id, uri_id);


insert into evolutions (name, description) values('262.sql', 'adding index on user_id x uri_id to uri_recommendation');

# --- !Downs
