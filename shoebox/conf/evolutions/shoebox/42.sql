# --- !Ups
-- MySQL
-- ======
-- ALTER TABLE normalized_uri MODIFY url varchar(3072) NOT NULL;
-- ALTER TABLE url MODIFY url varchar(3072) NOT NULL;

-- H2
-- ======
ALTER TABLE normalized_uri ALTER COLUMN url varchar(3072) NOT NULL;
ALTER TABLE url ALTER COLUMN url varchar(3072) NOT NULL;

insert into evolutions (name, description) values('42.sql', 'use longer uri string');

# --- !Downs
