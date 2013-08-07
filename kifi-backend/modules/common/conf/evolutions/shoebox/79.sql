# --- !Ups

CREATE INDEX bookmark_i_updated_at ON bookmark(updated_at);

insert into evolutions (name, description) values('79.sql', 'add updated at index to the bookmark table');

# --- !Downs