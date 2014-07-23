#Curator


CREATE TABLE last_ingestion_time (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NULL,
    last_ingestion_time datetime NOT NULL,
);

insert into evolutions (name, description) values('205.sql', 'adding new tables for track last ingestion time');

# --- !Downs