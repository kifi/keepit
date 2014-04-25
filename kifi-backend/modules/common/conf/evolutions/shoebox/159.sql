# SHOEBOX

# --- !Ups

CREATE TABLE rekeep(

    id bigint(20)       NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
	state varchar(20)   NOT NULL,

    keeper_id bigint(20)  NOT NULL,
    keep_id bigint(20)  NOT NULL,
    uri_id bigint(20)   NOT NULL,

    src_user_id bigint(20) NOT NULL,
    src_keep_id bigint(20) NOT NULL,

    attr_factor int NOT NULL DEFAULT 1,

  	PRIMARY KEY (id),

    CONSTRAINT rekeep_keeper_id     FOREIGN KEY (keeper_id)     REFERENCES user(id),
    CONSTRAINT rekeep_src_user_id   FOREIGN KEY (src_user_id)   REFERENCES user(id),
    CONSTRAINT rekeep_uri_id        FOREIGN KEY (uri_id)        REFERENCES normalized_uri(id),
    CONSTRAINT rekeep_keep_id       FOREIGN KEY (keep_id)       REFERENCES bookmark(id),
    CONSTRAINT rekeep_src_keep_id   FOREIGN KEY (src_keep_id)   REFERENCES bookmark(id)
);

insert into evolutions (name, description) values('159.sql', 'adding rekeeps table');

# --- !Downs
