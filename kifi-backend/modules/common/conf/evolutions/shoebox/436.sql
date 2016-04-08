# SHOEBOX

# --- !Ups

create table keep_event (
  id bigint(20) not null auto_increment,
  created_at datetime not null,
  updated_at datetime not null,
  state varchar(20) not null,
  keep_id bigint(20) not null,
  event_data mediumtext not null,
  event_time datetime not null,
  source varchar(30) default null,

  primary key(id),
  constraint keep_event_f_keep_id foreign key (keep_id) references bookmark(id)
);

insert into evolutions (name, description) values('436.sql', 'add keep_event');

# --- !Downs
