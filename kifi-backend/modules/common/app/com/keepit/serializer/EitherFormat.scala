package com.keepit.serializer

import play.api.libs.json.{ Json, Format, JsValue }

/* Be careful, this will always attempt to deserialize to Left first, and then to Right if Left has failed.
This could lead to an incorrect behavior if the Right type can be serialized to a valid Left type
So we're not providing this Format as an implicit on purpose. */

object EitherFormat {
  def apply[A, B](implicit leftFormat: Format[A], rightFormat: Format[B]) = new Format[Either[A, B]] {
    def reads(json: JsValue) = json.validate[A].map(Left(_)) orElse json.validate[B].map(Right(_))
    def writes(either: Either[A, B]) = either.left.map(Json.toJson(_)).right.map(Json.toJson(_)).merge
  }
}

