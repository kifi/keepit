# HEIMDAL

# --- !Ups

ALTER TABLE delighted_answer ADD COLUMN external_id varchar(36);
ALTER TABLE delighted_answer ADD CONSTRAINT delighted_answer_u_external_id UNIQUE (external_id);
-- UPDATE delighted_answer SET external_id = UUID(); -- Prod
CREATE INDEX delighted_answer_i_ext_id ON delighted_answer (external_id); -- Dev
-- CREATE INDEX delighted_answer_i_ext_id ON delighted_answer (external_id(8)); -- Prod

insert into evolutions (name, description) values('201.sql', 'add external_id to delighted_answer table');

# --- !Downs
