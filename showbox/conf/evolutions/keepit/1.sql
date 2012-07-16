# --- !Ups

CREATE TABLE bookmark (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    title varchar(1024) NOT NULL,
    url varchar(1024) NOT NULL,
    normalized_url varchar(1024) NOT NULL,
    url_hash varchar(64) NOT NULL,
    bookmark_path varchar(256),
    is_private boolean,
    
    PRIMARY KEY (id),
    
    CONSTRAINT user_u_external_id UNIQUE (external_id)
);

# --- !Downs
