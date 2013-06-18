# --- !Ups

CREATE TABLE uri_topic (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    uri_id bigint(20) NOT NULL,
    topic BLOB NOT NULL,
    primaryTopic SMALLINT UNSIGNED NOT NULL,
    secondaryTopic SMALLINT UNSIGNED NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uri_topic_uri_id FOREIGN KEY (uri_id) REFERENCES normalized_uri(id),
    UNIQUE INDEX uri_topic_i_uri_id(uri_id)
);

insert into evolutions (name, description) values('64.sql', 'adding uri_topic table');

# --- !Downs