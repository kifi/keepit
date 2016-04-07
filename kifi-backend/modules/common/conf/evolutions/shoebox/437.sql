# SHOEBOX

# --- !Ups

alter table keep_event add message_id bigint(20) default null;
create unique index keep_event_u_message_id on keep_event (message_id);

# --- !Downs
