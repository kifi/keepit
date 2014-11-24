# CURATOR

# --- !Ups

CREATE TABLE curator_library_info (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    library_id bigint(20) NOT NULL,
    owner_id bigint(20) NOT NULL,
    member_count int NOT NULL,
    keep_count int NOT NULL,
    visibility varchar(32) NOT NULL,
    last_kept DATETIME,
    last_followed DATETIME,
    state varchar(20) NOT NULL,
    kind varchar(64) NOT NULL,
    library_last_updated DATETIME NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX curator_library_info_u_keep_id (library_id),
    INDEX curator_library_info_i_library_id_state (library_id, state)
);

insert into evolutions (name, description) values('264.sql', 'create curator_library_info table');

# --- !Downs
