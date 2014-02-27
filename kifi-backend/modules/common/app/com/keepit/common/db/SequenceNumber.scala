package com.keepit.common.db

import play.api.libs.json._
import play.api.mvc.{PathBindable, QueryStringBindable}

case class SequenceNumber[T](value: Long) extends Ordered[SequenceNumber[T]] {
  def compare(that: SequenceNumber[T]) = value compare that.value
  def +(offset: Long): SequenceNumber[T] = SequenceNumber[T](this.value + offset)
  def -(other: SequenceNumber[T]): Long = this.value - other.value
  def max(other: SequenceNumber[T]): SequenceNumber[T] = SequenceNumber[T](this.value max other.value)
  override def toString = value.toString
}



object SequenceNumber {
  def ZERO[T] = SequenceNumber[T](0)
  def MinValue[T] = SequenceNumber[T](Long.MinValue)
  implicit def format[T] = new Format[SequenceNumber[T]] {
    def reads(json: JsValue): JsResult[SequenceNumber[T]] = __.read[Long].reads(json).map(SequenceNumber[T](_))
    def writes(o: SequenceNumber[T]): JsValue = JsNumber(o.value)
  }

  implicit def queryStringBinder[T](implicit longBinder: QueryStringBindable[Long]) = new QueryStringBindable[SequenceNumber[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, SequenceNumber[T]]] = {
      longBinder.bind(key, params) map {
        case Right(value) => Right(SequenceNumber[T](value))
        case _ => Left("Unable to bind a SequenceNumber")
      }
    }
    override def unbind(key: String, seqNum: SequenceNumber[T]): String = {
      longBinder.unbind(key, seqNum.value)
    }
  }

  implicit def pathBinder[T](implicit longBinder: PathBindable[Long]) = new PathBindable[SequenceNumber[T]] {
    override def bind(key: String, value: String): Either[String, SequenceNumber[T]] =
      longBinder.bind(key, value) match {
        case Right(seqNumValue) => Right(SequenceNumber[T](seqNumValue))
        case _ => Left("Unable to bind a SequenceNumber")
      }
    override def unbind(key: String, seqNum: SequenceNumber[T]): String = seqNum.value.toString
  }
}

