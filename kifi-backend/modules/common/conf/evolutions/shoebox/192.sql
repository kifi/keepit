# SHOEBOX

# --- !Ups

CREATE TABLE library (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    name varchar(256) NOT NULL,
    owner_id bigint(20) NOT NULL,
    description varchar(512),
    visibility varchar(20) NOT NULL,
    tokens varchar(50) NOT NULL,

    PRIMARY KEY (id),
    FOREIGN KEY (ownerId) REFERENCES user(id)
);

CREATE TABLE library_member (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    library_id bigint(20) NOT NULL,
    user_id bigint(20) NOT NULL,
    access varchar(20) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    PRIMARY KEY (id),
    FOREIGN KEY (userId) REFERENCES user(id),
    FOREIGN KEY (libraryId) REFERENCES library(id)
    CONSTRAINT `library_member_f_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
    CONSTRAINT `library_member_f_library` FOREIGN KEY (`library_id`) REFERENCES `library` (`id`)
);

insert into evolutions (name, description) values('192.sql', 'adding new tables for library keeps');

# --- !Downs
