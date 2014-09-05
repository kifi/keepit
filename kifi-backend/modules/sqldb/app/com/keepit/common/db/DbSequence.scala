package com.keepit.common.db

import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }

/*

MySQL:

CREATE TABLE $sequenceName (id INT NOT NULL);
INSERT INTO $sequenceName VALUES (0);

H2:

CREATE SEQUENCE $sequenceName;

*/

abstract class DbSequence[T](val name: String) {
  if (DbSequence.validSequenceName.findFirstIn(name).isEmpty)
    throw new IllegalArgumentException(s"Sequence name $name is invalid")
  def incrementAndGet()(implicit session: RWSession): SequenceNumber[T]
  def getLastGeneratedSeq()(implicit session: RSession): SequenceNumber[T]
  def reserve(n: Int)(implicit session: RWSession): SequenceNumberRange[T]
}

object DbSequence {
  val validSequenceName = "^([a-zA-Z_][a-zA-Z0-9_]*)$".r
}
