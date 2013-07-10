# --- !Ups

CREATE TABLE user_topic_b (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    topic BLOB NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT user_topic_b_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    UNIQUE INDEX user_topic_b_i_user_id(user_id)
);


CREATE TABLE uri_topic_b (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    uri_id bigint(20) NOT NULL,
    topic BLOB NOT NULL,
    primaryTopic SMALLINT UNSIGNED,
    secondaryTopic SMALLINT UNSIGNED,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uri_topic_b_uri_id FOREIGN KEY (uri_id) REFERENCES normalized_uri(id),
    UNIQUE INDEX uri_topic_b_i_uri_id(uri_id)
);

CREATE TABLE topic_seq_num_info_b (
    id bigint(20) NOT NULL AUTO_INCREMENT,    
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    uri_seq bigint(20) NOT NULL,
    bookmark_seq bigint(20) NOT NULL,
    PRIMARY KEY (id)
);

insert into evolutions (name, description) values('75.sql', 'adding tables: user_topic_b, uri_topic_b, topic_seq_num_info_b');

# --- !Downs