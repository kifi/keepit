# SHOEBOX

# --- !Ups

CREATE TABLE library_invite (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    library_id bigint(20) NOT NULL,
    owner_id bigint(20) NOT NULL,
    user_id bigint(20) NOT NULL,
    access varchar(20) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT `library_invite_f_owner` FOREIGN KEY (`owner_id`) REFERENCES `user` (`id`),
    CONSTRAINT `library_invite_f_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`),
    CONSTRAINT `library_invite_f_library` FOREIGN KEY (`library_id`) REFERENCES `library` (`id`)
);

insert into evolutions (name, description) values('196.sql', 'adding new tables for library invites');

# --- !Downs
