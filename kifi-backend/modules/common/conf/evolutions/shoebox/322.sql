# ROVER

# --- !Ups

ALTER TABLE article_info add column last_image_processing_version_major int NULL;
ALTER TABLE article_info add column last_image_processing_version_minor int NULL;
ALTER TABLE article_info add column last_image_processing_at datetime NULL;

CREATE TABLE if not exists rover_image_info (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    format varchar(16) NOT NULL,
    width smallint unsigned NOT NULL,
    height smallint unsigned NOT NULL,
    kind varchar(32) NOT NULL,
    path varchar(64) NOT NULL,
    source varchar(32) NOT NULL,
    source_image_hash varchar(32) NOT NULL,
    source_image_url varchar(3072) NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX rover_image_info_u_source_image_hash_size_kind_format (source_image_hash, width, height, kind, format)
);

CREATE TABLE if not exists article_image (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    kind varchar(64) NOT NULL,
    version_major int NOT NULL,
    version_minor int NOT NULL,
    fetched_at datetime NOT NULL,
    image_url varchar(3072) NOT NULL,
    image_hash varchar(32) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX article_image_u_uri_id_kind_image_hash (uri_id, kind, image_hash),
    CONSTRAINT article_image_f_rover_image_info FOREIGN KEY (image_hash) REFERENCES rover_image_info(source_image_hash),
    CONSTRAINT article_image_f_article_info FOREIGN KEY (uri_id, kind) REFERENCES article_info(uri_id, kind)
);

insert into evolutions (name, description) values('322.sql', 'create rover_image_info, article_image tables');

# --- !Downs
