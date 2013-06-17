# --- !Ups

CREATE TABLE user_experiment (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    experiment_type varchar(20) NOT NULL,
    state varchar(20) NOT NULL,
    
    PRIMARY KEY (id),
    
    UNIQUE INDEX user_experiment_u_user_id_state_experiment_type (user_id, state, experiment_type),
    
    CONSTRAINT user_experiment_user_id FOREIGN KEY (user_id) REFERENCES user(id)
);

insert into evolutions (name, description) values('11.sql', 'adding user experiment');

# --- !Downs
