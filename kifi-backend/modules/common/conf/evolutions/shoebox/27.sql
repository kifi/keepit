# --- !Ups

CREATE TABLE unscrapable (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    pattern varchar(256) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    
    PRIMARY KEY (id),
    INDEX unscrapable_state (state)
);

insert into evolutions (name, description) values('27.sql', 'adding unscrapable table');

# --- !Downs
