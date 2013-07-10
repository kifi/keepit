package com.keepit.common.db

import play.api.libs.json._

case class SequenceNumber(value: Long) extends AnyVal with Ordered[SequenceNumber] {
  def compare(that: SequenceNumber) = value compare that.value
  override def toString = value.toString
}



object SequenceNumber {
  val ZERO = SequenceNumber(0)
  val MinValue = SequenceNumber(Long.MinValue)
  implicit val sequenceNumberFormat = new Format[SequenceNumber] {
    def reads(json: JsValue): JsResult[SequenceNumber] = __.read[Long].reads(json).map(SequenceNumber(_))
    def writes(o: SequenceNumber): JsValue = JsNumber(o.value)
  }
}

