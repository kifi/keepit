# CURATOR

# --- !Ups

CREATE TABLE IF NOT EXISTS system_value (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    name varchar(64) NOT NULL,
    value TEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX system_value_name (name)
);

insert into evolutions (name, description) values('199.sql', 'adding system_value table to curator');

# --- !Downs
