# SHOEBOX

# --- !Ups

CREATE TABLE library (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    name varchar(256) NOT NULL,
    ownerId bigint(20) NOT NULL,
    description varchar(512),
    privacy varchar(20),
    tokens varchar(512) NOT NULL,

    PRIMARY KEY (id),
    FOREIGN KEY (ownerId) REFERENCES user(id)
);

CREATE TABLE library_member (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    libraryId bigint(20) NOT NULL,
    userId bigint(20) NOT NULL,
    permission varchar(20) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    PRIMARY KEY (id),
    FOREIGN KEY (userId) REFERENCES user(id),
    FOREIGN KEY (libraryId) REFERENCES library(id)
);

insert into evolutions (name, description) values('191.sql', 'adding new tables for library keeps');


# --- !Downs
