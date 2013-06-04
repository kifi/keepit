# --- !Ups

CREATE INDEX bookmark_i_created_at ON bookmark(created_at);

insert into evolutions (name, description) values('6.sql', 'add index on bookmarks creation time');

# --- !Downs
