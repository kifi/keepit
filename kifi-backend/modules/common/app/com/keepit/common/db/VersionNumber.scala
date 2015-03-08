package com.keepit.common.db

import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

case class VersionNumber[T](value: Int) extends Ordered[VersionNumber[T]] {
  def compare(that: VersionNumber[T]) = value compare that.value
  def +(offset: Int): VersionNumber[T] = VersionNumber[T](this.value + offset)
  def -(other: VersionNumber[T]): Int = this.value - other.value
  def max(other: VersionNumber[T]): VersionNumber[T] = VersionNumber[T](this.value max other.value)
  override def toString = value.toString
}

case class VersionNumberRange[T](start: Int, end: Int) extends Iterable[VersionNumber[T]] {
  def iterator: Iterator[VersionNumber[T]] = (start to end).iterator.map(VersionNumber[T](_))
}

object VersionNumber {
  def ZERO[T] = VersionNumber[T](0)
  def MinValue[T] = VersionNumber[T](-1)
  implicit def format[T]: Format[VersionNumber[T]] = new Format[VersionNumber[T]] {
    def reads(json: JsValue): JsResult[VersionNumber[T]] = json.validate[Int].map(VersionNumber[T](_))
    def writes(o: VersionNumber[T]): JsValue = JsNumber(o.value)
  }

  implicit def queryStringBinder[T](implicit longBinder: QueryStringBindable[Int]) = new QueryStringBindable[VersionNumber[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, VersionNumber[T]]] = {
      longBinder.bind(key, params) map {
        case Right(value) => Right(VersionNumber[T](value))
        case _ => Left("Unable to bind a VersionNumber")
      }
    }
    override def unbind(key: String, version: VersionNumber[T]): String = {
      longBinder.unbind(key, version.value)
    }
  }

  implicit def pathBinder[T](implicit longBinder: PathBindable[Int]) = new PathBindable[VersionNumber[T]] {
    override def bind(key: String, value: String): Either[String, VersionNumber[T]] =
      longBinder.bind(key, value) match {
        case Right(seqNumValue) => Right(VersionNumber[T](seqNumValue))
        case _ => Left("Unable to bind a VersionNumber")
      }
    override def unbind(key: String, version: VersionNumber[T]): String = version.value.toString
  }
}

