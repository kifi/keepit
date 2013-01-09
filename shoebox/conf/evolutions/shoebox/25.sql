# --- !Ups

CREATE TABLE url (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    url varchar(2048) NOT NULL,
    domain varchar(512),
    normalized_uri_id bigint(20) NOT NULL,
    history varchar(2048),
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    
    PRIMARY KEY (id),
    CONSTRAINT url_normalized_uri_id FOREIGN KEY (normalized_uri_id) REFERENCES normalized_uri(id)
);

alter TABLE bookmark
  add column url_id bigint(20);
alter TABLE comment
  add column url_id bigint(20);
alter TABLE deep_link
  add column url_id bigint(20);
alter TABLE follow
  add column url_id bigint(20);
alter TABLE scrape_info
  add column url_id bigint(20);

alter TABLE normalized_uri
  add column domain varchar(512);

insert into evolutions (name, description) values('25.sql', 'adding uri_data (JSON) to all tables that use a normalized_uri_id');

# --- !Downs
