package com.keepit.graph.wander

import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, User}
import play.api.libs.json._

case class Recommendations(users: Map[Id[User], Int], uris: Map[Id[NormalizedURI], Int], extra: Map[String, Int])

object Recommendations {
  private implicit def idMapFormat[T] = new Format[Map[Id[T], Int]] {
    def reads(json: JsValue) = Json.fromJson[JsObject](json).map(_.value.map { case (idStr, count) => Id[T](idStr.toLong) -> count.as[Int] }.toMap)
    def writes(idMap: Map[Id[T], Int]) = JsObject(idMap.map { case (id, count) => id.id.toString -> JsNumber(count) }.toSeq)
  }

  implicit val format = Json.format[Recommendations]
}
