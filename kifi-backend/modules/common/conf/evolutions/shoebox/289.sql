# SHOEBOX

# --- !Ups

ALTER TABLE bookmark modify COLUMN visibility varchar(32) NOT NULL;
create index bookmark_kept_at on bookmark(kept_at);
create index bookmark_visibility on bookmark(visibility);

insert into evolutions (name, description) values('289.sql', 'making visibility in bookmark be NOT NULL');

# --- !Downs
