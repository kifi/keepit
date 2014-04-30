package com.keepit.cortex

import play.api.libs.json._
import play.api.mvc.{PathBindable, QueryStringBindable}

case class CortexVersionedSequenceNumber[T](version: Int, unversionedSeq: Long){
  def versionedSeq: Long =  CortexVersionedSequenceNumber.toLong(this)
}

object CortexVersionedSequenceNumber {
  // top 8 bits reserved for version
  val maxSeq = (1L << 56) - 1

  implicit def format[T] = new Format[CortexVersionedSequenceNumber[T]]{
    def reads(json: JsValue): JsResult[CortexVersionedSequenceNumber[T]] = {
      val version = (json \ "version").as[Int]
      val seq = (json \ "unversionedSeq").as[Long]
      JsSuccess(CortexVersionedSequenceNumber[T](version, seq))
    }

    def writes(o: CortexVersionedSequenceNumber[T]) = Json.obj("version"-> o.version, "unversionedSeq" -> o.unversionedSeq)
  }

  def toLong[T](vseq: CortexVersionedSequenceNumber[T]) = {
    assume(vseq.unversionedSeq <= maxSeq)
    vseq.version.toLong << 56 | vseq.unversionedSeq
  }

  def fromLong[T](x: Long): CortexVersionedSequenceNumber[T] = {
    val version = x >> 56
    val seq = ~(0xFFL << 56) & x
    CortexVersionedSequenceNumber(version.toInt, seq)
  }

  implicit def queryStringBinder[T](implicit longBinder: QueryStringBindable[Long]) = new QueryStringBindable[CortexVersionedSequenceNumber[T]]{
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, CortexVersionedSequenceNumber[T]]] = {
      longBinder.bind(key, params) map {
        case Right(value) => Right(CortexVersionedSequenceNumber.fromLong[T](value))
        case _ => Left("Unable to bind a cortex versioned sequenceNumber")
      }
      None
    }

    override def unbind(key: String, vseq: CortexVersionedSequenceNumber[T]): String = {
      longBinder.unbind(key, CortexVersionedSequenceNumber.toLong(vseq))
    }
  }
}

