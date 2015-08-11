# SHOEBOX

# --- !Ups

alter table notification add COLUMN group_identifier VARCHAR(256) NOT NULL;

alter table notification add CONSTRAINT notification_group_identifier unique index(group_identifier);

alter table notification add COLUMN recipient VARCHAR(256) not null DEFAULT('');

update notification set recipient = 'user:' + user_id;

alter table notification modify COLUMN recipient VARCHAR(256) not null;
alter table notification drop column user_id;

-- # Replaces the JSON, really ugly but it works. Commented out because this happens manually after the evolution
-- # update notification_item set event = replace(replace(event, 'userId":', 'recipient":"user|'), ',"time', '","time');

alter table notification add column last_event DATETIME not null;

alter table notification add column disabled BOOLEAN not null default false;

insert into evolutions (name, description) values('373.sql', 'Add group_identifier, recipient, last_event, and disabled to notification');

# --- !Downs
