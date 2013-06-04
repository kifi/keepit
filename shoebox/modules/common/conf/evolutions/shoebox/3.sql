# --- !Ups

CREATE INDEX normalized_uri_i_state ON normalized_uri(state);

insert into evolutions (name, description) values('3.sql', 'adding index on normalized_uri(state)');

# --- !Downs
