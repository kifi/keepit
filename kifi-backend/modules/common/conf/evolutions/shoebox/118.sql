# SHOEBOX

# --- !Ups

CREATE TABLE password_reset (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    state VARCHAR(20) NOT NULL,
    token VARCHAR(64) NOT NULL,
    used_at datetime NULL,
    used_by_ip VARCHAR(16) NULL,
    sent_to MEDIUMTEXT NULL,
    PRIMARY KEY (id),

    CONSTRAINT password_reset_f_user_id FOREIGN KEY (user_id) REFERENCES user(id)
);

insert into evolutions (name, description) values('118.sql', 'adding password reset tables');

# --- !Downs
