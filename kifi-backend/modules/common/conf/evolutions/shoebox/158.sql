# HEIMDAL

# --- !Ups

CREATE TABLE keep_click(

    id bigint(20)       NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20)   NOT NULL,

    search_uuid varchar(36) NOT NULL,
    num_keepers int  NOT NULL DEFAULT 1,

    keeper_id bigint(20)  NOT NULL,
    keep_id bigint(20)  NOT NULL,
    uri_id bigint(20)   NOT NULL,

    clicker_id bigint(20) NOT NULL,

    PRIMARY KEY (id),

    INDEX keep_click_uuid(search_uuid)
);

insert into evolutions (name, description) values('158.sql', 'adding keep_click table');


# --- !Downs
