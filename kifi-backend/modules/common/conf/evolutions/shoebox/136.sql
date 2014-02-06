#Shoebox

# --- !Ups

CREATE TABLE probabilistic_experiment_generator (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    description tinytext NOT NULL,
    cond varchar(20) NULL,
    salt varchar(32) NOT NULL,
    density text NOT NULL,

    PRIMARY KEY (id),
    INDEX probabilistic_experiment_generator_state (state)
);

insert into evolutions (name, description) values('136.sql', 'create table probabilistic_experiment_generator');

# --- !Downs
