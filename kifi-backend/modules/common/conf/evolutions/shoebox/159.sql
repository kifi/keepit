# HEIMDAL

# --- !Ups

CREATE TABLE rekeep(

    id bigint(20)       NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20)   NOT NULL,

    keeper_id bigint(20)  NOT NULL,
    keep_id bigint(20)  NOT NULL,
    uri_id bigint(20)   NOT NULL,

    src_user_id bigint(20) NOT NULL,
    src_keep_id bigint(20) NOT NULL,

    attr_factor int NOT NULL DEFAULT 1,

    PRIMARY KEY (id)
);

insert into evolutions (name, description) values('159.sql', 'adding rekeep table');

# --- !Downs
