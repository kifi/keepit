# --- !Ups

CREATE INDEX normalized_uri_redirection on normalized_uri(redirect);

insert into evolutions (name, description) values('92.sql', 'add index on normalized uri redirection');

# --- !Downs
