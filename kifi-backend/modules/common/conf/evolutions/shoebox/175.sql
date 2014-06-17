# SHOEBOX

# --- !Ups

alter table normalized_uri modify seq bigint(20);

alter table bookmark modify seq bigint(20);
alter table collection modify seq bigint(20);
alter table email_address modify seq bigint(20);
alter table invitation modify seq bigint(20);
alter table phrase modify seq bigint(20);
alter table search_friend modify seq bigint(20);
alter table social_connection modify seq bigint(20);
alter table social_user_info modify seq bigint(20);
alter table user modify seq bigint(20);
alter table user_connection modify seq bigint(20);

insert into evolutions (name, description) values('175.sql', 'use long for seq columns');

# --- !Downs
