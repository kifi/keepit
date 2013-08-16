# --- !Ups


CREATE TABLE uri_renormalization_event (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    sequence_number bigint(20) NOT NULL,
    num_ids_changed bigint(20) NOT NULL,
    ids_retired longtext NOT NULL,

    PRIMARY KEY (id),
    KEY uri_renormalization_event_i_sequence_number (sequence_number)
);


insert into evolutions (name, description) values('7.sql', 'adding new table for tracking normalized uri changes');



# --- !Downs
