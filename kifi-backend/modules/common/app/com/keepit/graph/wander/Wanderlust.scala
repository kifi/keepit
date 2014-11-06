package com.keepit.graph.wander

import com.keepit.common.db.Id
import com.keepit.model.{ Library, SocialUserInfo, NormalizedURI, User }
import play.api.libs.json._
import scala.concurrent.duration._

case class Wanderlust(
  startingVertexKind: String,
  startingVertexDataId: Long,
  preferredCollisions: Set[String] = Set.empty,
  avoidTrivialCollisions: Boolean = true,
  steps: Int = 100000,
  restartProbability: Double = 0.15,
  recency: Option[Duration] = None,
  halfLife: Option[Duration] = None)

object Wanderlust {

  implicit val format = {
    implicit val durationFormat = new Format[Duration] {
      def reads(json: JsValue) = json.validate[Long].map(Duration(_, "millis"))
      def writes(duration: Duration) = JsNumber(duration.toMillis)
    }
    Json.format[Wanderlust]
  }

  def discovery(userId: Id[User]) = Wanderlust(
    startingVertexKind = "User",
    startingVertexDataId = userId.id,
    preferredCollisions = Set(), // will walk all types of nodes in graph if it's empty set
    avoidTrivialCollisions = true,
    steps = 100000,
    restartProbability = 0.15,
    recency = Some(120 days),
    halfLife = Some(7 days)
  )
}

case class Collisions(users: Map[Id[User], Int], socialUsers: Map[Id[SocialUserInfo], Int], libraries: Map[Id[Library], Int], uris: Map[Id[NormalizedURI], Int], extra: Map[String, Int])

object Collisions {
  implicit def idMapFormat[T] = new Format[Map[Id[T], Int]] {
    def reads(json: JsValue) = Json.fromJson[JsObject](json).map(_.value.map { case (idStr, count) => Id[T](idStr.toLong) -> count.as[Int] }.toMap)
    def writes(idMap: Map[Id[T], Int]) = JsObject(idMap.map { case (id, count) => id.id.toString -> JsNumber(count) }.toSeq)
  }

  implicit val format = Json.format[Collisions]

  val empty = Collisions(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)
}
