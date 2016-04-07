#Shoebox

# --- !Ups
ALTER TABLE bookmark DROP CONSTRAINT IF EXISTS lib_primary_uri;

-- MySQL:
-- ALTER TABLE bookmark DROP KEY lib_primary_uri;

insert into evolutions (name, description) values('439.sql', 'drop lib_primary_uri constraint on bookmark');

# --- !Downs
