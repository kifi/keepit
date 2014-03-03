# SHOEBOX

# --- !Ups

create index normalized_uri_idx_restriction on normalized_uri (restriction);

insert into evolutions (name, description) values('140.sql', 'add restriction index on normalized_uri');

# --- !Downs
