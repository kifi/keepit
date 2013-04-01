# --- !Ups

ALTER TABLE user_experiment ALTER COLUMN experiment_type VARCHAR(32) NOT NULL;
# ALTER TABLE user_experiment MODIFY COLUMN experiment_type VARCHAR(32) NOT NULL;

insert into evolutions (name, description) values('48.sql', 'increase size of experiment_type column');

# --- !Downs
