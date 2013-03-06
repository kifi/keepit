# --- !Ups
-- MySQL
-- ======
-- ALTER TABLE search_config_experiment ADD started_at datetime;

-- H2
-- ======
ALTER TABLE search_config_experiment ADD started_at datetime;

insert into evolutions (name, description) values('43.sql', 'add start time to search config experiments');

# --- !Downs
