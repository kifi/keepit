package com.keepit.common

import play.api.libs.json._
import play.api.data.validation.ValidationError

package object json {

  /* Inspired from https://coderwall.com/p/orci8g */
  implicit def tuple2Reads[A, B](implicit aReads: Reads[A], bReads: Reads[B]): Reads[Tuple2[A, B]] = Reads[Tuple2[A, B]] {
    case JsArray(arr) if arr.size == 2 => for {
      a <- aReads.reads(arr(0))
      b <- bReads.reads(arr(1))
    } yield (a, b)
    case _ => JsError(Seq(JsPath() -> Seq(ValidationError("Expected array of two elements"))))
  }

  implicit def tuple2Writes[A, B](implicit aWrites: Writes[A], bWrites: Writes[B]): Writes[Tuple2[A, B]] = new Writes[Tuple2[A, B]] {
    def writes(tuple: Tuple2[A, B]) = JsArray(Seq(aWrites.writes(tuple._1), bWrites.writes(tuple._2)))
  }

  implicit def tuple2Format[A, B](implicit reads: Reads[(A, B)], writes: Writes[(A, B)]) = {
    Format(reads, writes)
  }

  implicit class PimpedFormat[A](format: Format[A]) {
    private implicit val implicitFormat: Format[A] = format
    def convert[B](atob: A => B, btoa: B => A): Format[B] = Format(
      Reads(Json.fromJson[A](_).map(atob)),
      Writes(obj => Json.toJson(btoa(obj)))
    )
  }

}
