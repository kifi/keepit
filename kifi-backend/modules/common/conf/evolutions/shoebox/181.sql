# ELIZA

# --- !Ups
CREATE TABLE message_search_history (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    user_id bigint(20) NOT NULL,
    opt_out bool NOT NULL,
    queries text NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY message_search_history_u_user_id (user_id)
);

insert into evolutions (name, description) values('181.sql', 'adding message search hisotry table');

# --- !Downs
