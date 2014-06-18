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

ALTER TABLE user ADD COLUMN if not exists primary_email varchar(512) NULL;

insert into evolutions (name, description) values('175.sql', 'add primary_email to user table and use long for seq columns');

# --- !Downs
