# SHOEBOX

# --- !Ups

CREATE TABLE uri_image (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    page_id bigint(20) NOT NULL,
    width int NOT NULL,
    height int NOT NULL,
    source varchar(32) NOT NULL,
    format varchar(32) NOT NULL,
    source_url varchar(2048) NOT NULL,
    state varchar(20) NOT NULL,

    PRIMARY KEY (id),
    
    CONSTRAINT uri_image_u_external_id UNIQUE (external_id),
    CONSTRAINT uri_image_f_uri FOREIGN KEY (page_id) REFERENCES normalized_uri(id)
);

insert into evolutions (name, description) values('157.sql', 'add uri_image table');

# --- !Downs
