# --- !Ups

CREATE TABLE slider_history (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    table_size SMALLINT UNSIGNED NOT NULL,
    filter BLOB NOT NULL,
    num_hash_funcs SMALLINT UNSIGNED NOT NULL,
    min_hits SMALLINT UNSIGNED NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT slider_history_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    UNIQUE INDEX slider_history_i_user_id(user_id)
);

insert into evolutions (name, description) values('36.sql', 'adding slider_history table');

# --- !Downs
