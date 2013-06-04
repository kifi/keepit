# --- !Ups

alter TABLE scrape_info
  add column destination_url varchar(2048);

CREATE TABLE duplicate_document (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    uri1_id bigint(20) NOT NULL,
    uri2_id bigint(20) NOT NULL,
    percent_match decimal(3,2) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    
    PRIMARY KEY (id),
    CONSTRAINT duplicate_document_uri1_id FOREIGN KEY (uri1_id) REFERENCES normalized_uri(id),
    CONSTRAINT duplicate_document_uri2_id FOREIGN KEY (uri2_id) REFERENCES normalized_uri(id)
);

insert into evolutions (name, description) values('26.sql', 'adding column destination_url to scrape_info, table duplicate_document');

# --- !Downs
