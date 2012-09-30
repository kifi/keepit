# --- !Ups

CREATE TABLE user (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    first_name varchar(256) NOT NULL,
    last_name varchar(256) NOT NULL,
    facebook_id varchar(16) NOT NULL,
    facebook_access_token varchar(512),
    
    PRIMARY KEY (id),
    
    CONSTRAINT user_u_external_id UNIQUE (external_id) 
);

CREATE TABLE normalized_uri (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    title varchar(2048) NOT NULL,
    url varchar(2048) NOT NULL,
    url_hash varchar(512) NOT NULL,
    is_private boolean,
    
    KEY (url_hash),
    PRIMARY KEY (id),
    
    CONSTRAINT normalized_uri_u_external_id UNIQUE (external_id), 
    CONSTRAINT normalized_uri_u_url_hash UNIQUE (url_hash) 
);


CREATE TABLE bookmark (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    user_id bigint(20) NOT NULL,
    title varchar(2048) NOT NULL,
    url varchar(2048) NOT NULL,
    uri_id bigint(20) NOT NULL,
    bookmark_path varchar(256),
    is_private boolean,
    
    KEY (user_id, uri_id),
    PRIMARY KEY (id),
    
    CONSTRAINT bookmark_u_external_id UNIQUE (external_id),
    CONSTRAINT bookmark_f_user FOREIGN KEY (user_id) REFERENCES user(id), 
    CONSTRAINT bookmark_f_uri FOREIGN KEY (uri_id) REFERENCES normalized_uri(id) 
);

CREATE TABLE evolutions (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at timestamp NOT NULL,
    name varchar(256) NOT NULL,
    description varchar(512) DEFAULT '',

    PRIMARY KEY (id),
    CONSTRAINT evolutions_u_name UNIQUE (name)
);

insert into evolutions (name, description) values('1.sql', 'adding user and bookmark');

# --- !Downs
