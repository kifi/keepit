package com.keepit.common.db

import com.keepit.common.db.slick.DBSession.RWSession
import scalax.io.JavaConverters._
import scala.util.matching.Regex
import play.api.libs.json._

/*

MySQL:

CREATE TABLE $sequenceName (id INT NOT NULL);
INSERT INTO $sequenceName VALUES (0);

H2:

CREATE SEQUENCE $sequenceName;

*/

case class SequenceNumber(value: Long) extends AnyVal with Ordered[SequenceNumber] {
  def compare(that: SequenceNumber) = value compare that.value
  override def toString = value.toString
}

object SequenceNumber {
  val ZERO = SequenceNumber(0)
  implicit val sequenceNumberFormat = new Format[SequenceNumber] {
    def reads(json: JsValue): JsResult[SequenceNumber] = __.read[Long].reads(json).map(SequenceNumber(_))
    def writes(o: SequenceNumber): JsValue = JsNumber(o.value)
  }
}

abstract class DbSequence(val name: String) {
  if (DbSequence.validSequenceName.findFirstIn(name).isEmpty)
    throw new IllegalArgumentException(s"Sequence name $name is invalid")
  def incrementAndGet()(implicit session: RWSession): SequenceNumber
}

object DbSequence {
  val validSequenceName = "^([a-zA-Z_][a-zA-Z0-9_]*)$".r
}
