package com.keepit.common.db

import play.api.libs.json._
import play.api.mvc.{PathBindable, QueryStringBindable}

case class SequenceNumber(value: Long) extends AnyVal with Ordered[SequenceNumber] {
  def compare(that: SequenceNumber) = value compare that.value
  def +(offset: Long): SequenceNumber = SequenceNumber(this.value + offset)
  def -(other: SequenceNumber): Long = this.value - other.value
  override def toString = value.toString
}



object SequenceNumber {
  val ZERO = SequenceNumber(0)
  val MinValue = SequenceNumber(Long.MinValue)
  implicit val sequenceNumberFormat = new Format[SequenceNumber] {
    def reads(json: JsValue): JsResult[SequenceNumber] = __.read[Long].reads(json).map(SequenceNumber(_))
    def writes(o: SequenceNumber): JsValue = JsNumber(o.value)
  }

  implicit def queryStringBinder(implicit longBinder: QueryStringBindable[Long]) = new QueryStringBindable[SequenceNumber] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, SequenceNumber]] = {
      longBinder.bind(key, params) map {
        case Right(value) => Right(SequenceNumber(value))
        case _ => Left("Unable to bind a SequenceNumber")
      }
    }
    override def unbind(key: String, seqNum: SequenceNumber): String = {
      longBinder.unbind(key, seqNum.value)
    }
  }

  implicit def pathBinder(implicit longBinder: PathBindable[Long]) = new PathBindable[SequenceNumber] {
    override def bind(key: String, value: String): Either[String, SequenceNumber] =
      longBinder.bind(key, value) match {
        case Right(seqNumValue) => Right(SequenceNumber(seqNumValue))
        case _ => Left("Unable to bind a SequenceNumber")
      }
    override def unbind(key: String, seqNum: SequenceNumber): String = seqNum.value.toString
  }
}

