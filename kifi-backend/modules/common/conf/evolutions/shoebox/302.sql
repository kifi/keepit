# SHOEBOX

# --- !Ups

CREATE TABLE activity_push_task (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    user_id bigint(20) NULL,
    state varchar(20) NOT NULL,

    last_push datetime NULL,
    last_active_time bigint(20) NOT NULL,
    last_active_date datetime NOT NULL,

    PRIMARY KEY (id),
    INDEX activity_push_task_i_push_active (last_push, last_active_time)
);

create unique index activity_push_task_uf_user on activity_push_task(user_id);
alter table activity_push_task add constraint activity_push_task_uf_user foreign key (user_id) references user(id) ON UPDATE CASCADE ON DELETE RESTRICT;


insert into evolutions (name, description) values('302.sql', 'create new table for push task activity');

# --- !Downs
