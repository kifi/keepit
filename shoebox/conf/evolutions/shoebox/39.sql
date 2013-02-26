# --- !Ups

CREATE TABLE phrase (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    phrase varchar(128) NOT NULL,
    source varchar(32) NOT NULL,
    lang varchar(5) NOT NULL,
    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),

    INDEX phrase_i_phrase_lang_state (phrase, lang, state)
);

insert into evolutions (name, description) values('39.sql', 'adding phrase table');

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
