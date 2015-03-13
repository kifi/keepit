# SHOEBOX

# --- !Ups

CREATE TABLE activity_push_task (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    user_id bigint(20) NULL,
    state varchar(20) NOT NULL,

    last_push datetime NULL,
    active_time datetime NOT NULL,
    active_time datetime NOT NULL,

    PRIMARY KEY (id),
    INDEX activity_push_task_i_push_active (last_push, active_time),
    CONSTRAINT activity_push_task_f_user FOREIGN KEY (user_id)
      REFERENCES user(id)
      ON UPDATE CASCADE ON DELETE RESTRICT
);


insert into evolutions (name, description) values('302.sql', 'create new table for push task activity');

# --- !Downs
