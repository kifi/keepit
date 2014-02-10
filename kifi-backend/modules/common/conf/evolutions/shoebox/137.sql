#Shoebox

# --- !Ups

ALTER TABLE probabilistic_experiment_generator ADD COLUMN name VARCHAR(64) NOT NULL;
ALTER TABLE probabilistic_experiment_generator DROP COLUMN description;
CREATE UNIQUE INDEX probabilistic_experiment_generator_name ON probabilistic_experiment_generator (name);

insert into evolutions (name, description) values('137.sql', 'replace probabilistic experiment generator description with name');

# --- !Downs
