#Curator


CREATE TABLE last_top_uri_ingestion_time (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    last_ingestion_time datetime NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX last_top_uri_ingestion_time_u_user_id (user_id)
);

insert into evolutions (name, description) values('205.sql', 'adding new tables for track last ingestion time');

# --- !Downs
