# --- !Ups

CREATE TABLE user_value (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    name varchar(64) NOT NULL,
    value TEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT user_value_f_user_id FOREIGN KEY (user_id) REFERENCES user(id),
    UNIQUE INDEX user_value_user_id_name (user_id, name)
);

insert into evolutions (name, description) values('49.sql', 'adding user_value table');

# --- !Downs
