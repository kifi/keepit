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

# --- !Downs
