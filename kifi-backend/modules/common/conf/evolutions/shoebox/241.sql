# CURATOR

# --- !Ups

CREATE TABLE if not exists curator_library_membership_info (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20) NOT NULL,
    library_id bigint(20) NOT NULL,
    library_access VARCHAR(64) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX curator_library_info_u_user_id_library_id (user_id, library_id)
);

insert into evolutions (name, description) values('241.sql', 'create curator library info');

# --- !Downs
