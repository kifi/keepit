package com.keepit.serializer

import play.api.libs.json.{ JsResult, Json, Format, JsValue }

/* Be careful, this will always attempt to deserialize to Left first, and then to Right if Left has failed.
This could lead to an incorrect behavior if the Right type can be serialized to a valid Left type
So we're not providing this Format as an implicit on purpose. */

object EitherFormat {
  def apply[A, B](implicit leftFormat: Format[A], rightFormat: Format[B]) = new Format[Either[A, B]] {
    def reads(json: JsValue) = json.validate[A].map(Left(_)) orElse json.validate[B].map(Right(_))
    def writes(either: Either[A, B]) = either.left.map(Json.toJson(_)).right.map(Json.toJson(_)).merge
  }
}

object TupleFormat {
  implicit def tuple2Format[T1, T2](implicit format1: Format[T1], format2: Format[T2]) = new Format[(T1, T2)] {
    def reads(json: JsValue): JsResult[(T1, T2)] = json.validate[Seq[JsValue]].map { case (Seq(_1, _2)) => (_1.as[T1], _2.as[T2]) }
    def writes(t: (T1, T2)): JsValue = Json.arr(format1.writes(t._1), format2.writes(t._2))
  }

  implicit def tuple3Format[T1, T2, T3](implicit format1: Format[T1], format2: Format[T2], format3: Format[T3]) = new Format[(T1, T2, T3)] {
    def reads(json: JsValue): JsResult[(T1, T2, T3)] = json.validate[Seq[JsValue]].map { case (Seq(_1, _2, _3)) => (_1.as[T1], _2.as[T2], _3.as[T3]) }
    def writes(t: (T1, T2, T3)): JsValue = Json.arr(format1.writes(t._1), format2.writes(t._2), format3.writes(t._3))
  }
}
