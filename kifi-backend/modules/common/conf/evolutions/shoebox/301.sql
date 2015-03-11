# CORTEX

# --- !Ups

alter table lda_related_library
    add index lda_related_library_i_version_dest (version, dest_id);

insert into evolutions (name, description) values('301.sql', 'add index in lda_related_library, support look up by dest_id');

# --- !Downs
