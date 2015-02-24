# SHOEBOX

# --- !Ups
CREATE TABLE if not exists twitter_waitlist (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    twitter_handle varchar(16) NOT NULL,
    state varchar(20) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX twitter_waitlist_u_user_id_twitter_handle (user_id, twitter_handle)
);

insert into evolutions (name, description) values('298.sql', 'create twitter waitlist table');

# --- !Downs
