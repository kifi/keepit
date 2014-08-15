# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  DROP COLUMN seen;

ALTER TABLE uri_recommendation
  ADD COLUMN delivered BIGINT(20) NOT NULL DEFAULT 0;

ALTER TABLE uri_recommendation
  MODIFY clicked BIGINT(20);

ALTER TABLE uri_recommendation
  ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE uri_recommendation
  ADD COLUMN marked_bad text;

INSERT INTO evolutions
            (name,
             description)
VALUES     ('228.sql',
'change uri_recommendation column names: seen->delivered, modify clicked column type: boolean -> bigint, adding new columns: deleted, markedBad'
);

# --- !Downs
