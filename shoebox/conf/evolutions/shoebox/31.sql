# --- !Ups

CREATE TABLE domain_tag (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    name varchar(128) NOT NULL,
    sensitive tinyint(1),
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    updates_count MEDIUMINT,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX domain_tag_name_index ON domain_tag (name);
CREATE UNIQUE INDEX domain_tag_name_status_index ON domain_tag (name, state);

CREATE TABLE domain (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    hostname varchar(128) NOT NULL,
    auto_sensitive tinyint(1),
    manual_sensitive tinyint(1),
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    updates_count MEDIUMINT,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX domain_hostname_index ON domain (hostname);
CREATE UNIQUE INDEX domain_hostname_status_index ON domain (hostname, state);

CREATE TABLE domain_to_tag (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    domain_id bigint(20) NOT NULL,
    tag_id bigint(20) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    updates_count MEDIUMINT,
    PRIMARY KEY (id),
    CONSTRAINT domain_to_tag_domain_id FOREIGN KEY (domain_id) REFERENCES domain(id),
    CONSTRAINT domain_to_tag_tag_id FOREIGN KEY (tag_id) REFERENCES domain_tag(id)
);

CREATE UNIQUE INDEX domain_to_tag_index ON domain_to_tag (domain_id, tag_id);
CREATE UNIQUE INDEX domain_to_tag_status_index ON domain_to_tag (domain_id, tag_id, state);

insert into evolutions (name, description) values('31.sql', 'adding domain tag tables');

# --- !Downs
