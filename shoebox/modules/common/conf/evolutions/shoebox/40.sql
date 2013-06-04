# --- !Ups

CREATE TABLE search_config_experiment (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    description VARCHAR(256) NOT NULL,
    weight DOUBLE NOT NULL,
    config TEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    INDEX search_config_experiment_state (state)
);

insert into evolutions (name, description) values('40.sql', 'adding search experiment tables');

# --- !Downs
