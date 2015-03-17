# SHOEBOX

# --- !Ups

alter table bookmark
    add column source_attribution_id bigint(20) NULL;

alter table bookmark
    add CONSTRAINT bookmark_f_source_attribution FOREIGN KEY (source_attribution_id) REFERENCES keep_source_attribution(id);

insert into evolutions (name, description) values('304.sql', 'add column source_attribution_id to bookmark table');


# --- !Downs
