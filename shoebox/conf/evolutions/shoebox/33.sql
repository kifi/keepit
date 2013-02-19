# --- !Ups
CREATE TABLE url_pattern (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    pattern varchar(2048) NOT NULL,
    example varchar(2048),
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id));

CREATE INDEX url_pattern__pattern_index ON url_pattern (pattern);

-- Note: escape backslashes below as \\ before running in MySQL
INSERT INTO url_pattern (pattern, example, state, created_at, updated_at) VALUES
    ('^https?://(www\.)?keepitfindit\.com', 'http://www.keepitfindit.com/', 'active', now(), now()),
    ('^https?://www\.facebook\.com', 'https://www.facebook.com/zuck', 'active', now(), now()),
    ('^https?://((www|drive|mail|maps|news|plus)\.)?google\.com', 'https://www.google.com/webhp?sourceid=chrome-instant&ion=1#hl=en&q=42', 'active', now(), now()),
    ('^https?://dev\.ezkeep\.com', 'http://dev.ezkeep.com:9000/admin/slider/patterns#_=_', 'active', now(), now()),
    ('^https?://localhost', 'http://localhost:3000/', 'active', now(), now());

INSERT INTO evolutions (name, description) VALUES ('33.sql', 'adding table for URL regular expression patterns');

# --- !Downs
