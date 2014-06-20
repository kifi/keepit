# SHOEBOX

# --- !Ups


alter table user_connection modify seq bigint(20);

-- MySQL:
-- alter table bookmark_sequence modify id bigint(20);
-- alter table changed_uri_sequence modify id bigint(20);
-- alter table collection_sequence modify id bigint(20); 
-- alter table email_address_sequence modify id bigint(20);
-- alter table image_info_sequence modify id bigint(20);
-- alter table invitation_sequence modify id bigint(20);
-- alter table normalized_uri_sequence modify id bigint(20);
-- alter table page_info_sequence modify id bigint(20);
-- alter table phrase_sequence modify id bigint(20);
-- alter table renormalized_url_sequence modify id bigint(20);
-- alter table search_friend_sequence modify id bigint(20);
-- alter table social_connection_sequence modify id bigint(20);
-- alter table social_user_info_sequence modify id bigint(20);
-- alter table user_connection_sequence modify id bigint(20);
-- alter table user_sequence modify id bigint(20);

insert into evolutions (name, description) values('177.sql', 'use long for seq column of user_connection and id columns of _sequence tables');

# --- !Downs
