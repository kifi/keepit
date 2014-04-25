# SHOEBOX

# --- !Ups

CREATE TABLE keep_click(

    id bigint(20)       NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
	state varchar(20)   NOT NULL,

    uuid varchar(36) NOT NULL,
    num_keepers int  NOT NULL DEFAULT 1,

    keeper_id bigint(20)  NOT NULL,
    keep_id bigint(20)  NOT NULL,
    uri_id bigint(20)   NOT NULL,

    clicker_id bigint(20) NOT NULL,

  	PRIMARY KEY (id),

  	CONSTRAINT keep_click_keeper_id  FOREIGN KEY (keeper_id)  REFERENCES user(id),
  	CONSTRAINT keep_click_clicker_id FOREIGN KEY (clicker_id) REFERENCES user(id),
  	CONSTRAINT keep_click_keep_id    FOREIGN KEY (keep_id) REFERENCES bookmark(id),
  	CONSTRAINT keep_click_uri_id     FOREIGN KEY (uri_id) REFERENCES normalized_uri(id),
  	INDEX keep_click_uuid(uuid)
);

insert into evolutions (name, description) values('158.sql', 'adding keep_click table');


# --- !Downs
