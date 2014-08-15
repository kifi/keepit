# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  CHANGE seen delivered BIGINT(20) NOT NULL DEFAULT 0;

ALTER TABLE uri_recommendation
  modify COLUMN clicked BIGINT(20);

ALTER TABLE uri_recommendation
  ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE uri_recommendation
  ADD COLUMN marked_bad text;

INSERT INTO evolutions
            (name,
             description)
VALUES     ('227.sql',
'change uri_recommendation column names: seen->delivered, modify clicked column type: boolean -> bigint, adding new columns: deleted, markedBad'
);

# --- !Downs
