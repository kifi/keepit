# HEIMDAL

# --- !Ups

CREATE TABLE delighted_user (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    user_id bigint(20) NOT NULL,

    PRIMARY KEY (id),
    KEY delighted_user_i_user_id (user_id)
);

CREATE TABLE delighted_answer (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    delighted_user_id bigint(20) NOT NULL,
    date datetime NOT NULL,
    score int NOT NULL,
    comment varchar(3072) NULL,

    PRIMARY KEY (id),

    FOREIGN KEY (delighted_user_id)
      REFERENCES delighted_user(id)
      ON UPDATE CASCADE ON DELETE RESTRICT
);

insert into evolutions (name, description) values('189.sql', 'adding new tables for delighted integration');

# --- !Downs
