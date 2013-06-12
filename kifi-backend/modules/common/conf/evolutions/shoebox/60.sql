# --- !Ups

CREATE TABLE collection (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    user_id bigint(20) NOT NULL,
    name varchar_ignorecase(64) NOT NULL, -- this is the same as MySQL varchar, should be case-insensitive

    KEY (user_id, name),
    PRIMARY KEY (id),

    CONSTRAINT collection_u_user_id_name UNIQUE (user_id, name),
    CONSTRAINT collection_u_external_id UNIQUE (external_id),
);

CREATE TABLE keep_to_collection (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    bookmark_id bigint(20) NOT NULL,
    collection_id bigint(20) NOT NULL,

    UNIQUE KEY (bookmark_id, collection_id),
    PRIMARY KEY (id),

    CONSTRAINT keep_to_collection_f_bookmark FOREIGN KEY (bookmark_id) REFERENCES bookmark(id),
    CONSTRAINT keep_to_collection_f_collection FOREIGN KEY (collection_id) REFERENCES collection(id),
);

insert into evolutions (name, description) values('60.sql', 'add collection tables');

# --- !Downs
