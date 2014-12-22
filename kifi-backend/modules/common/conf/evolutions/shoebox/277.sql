# CORTEX

# --- !Ups

alter table lda_info add column pmi_score real default NULL;

INSERT INTO evolutions (name, description) VALUES('277.sql', 'add pmi score to lda_info');


# --- !Downs
