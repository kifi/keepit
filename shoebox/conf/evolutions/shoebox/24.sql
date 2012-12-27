# --- !Ups

CREATE TABLE scrape_info (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    uri_id bigint(20) NOT NULL,
    last_scrape datetime NOT NULL,
    next_scrape datetime NOT NULL,
    scrape_interval double NOT NULL,
    failures integer NOT NULL,
    state varchar(20) NOT NULL,
    signature varchar(2048) NOT NULL,
    
    PRIMARY KEY (id),
    CONSTRAINT scrape_info_f_normalized_uri FOREIGN KEY (uri_id) REFERENCES normalized_uri(id),
    UNIQUE INDEX scrape_info_i_uri_id(uri_id),
    INDEX scrape_info_i_next_scrape(next_scrape)
);

insert into evolutions (name, description) values('24.sql', 'adding scape_info');

# --- !Downs
