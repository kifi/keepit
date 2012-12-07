package com.keepit.common.analytics

import com.keepit.common.db.Id
import org.joda.time.DateTime
import com.keepit.model.User
import play.api.libs.json.JsObject
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoServices.currentService
import com.keepit.common.controller.FortyTwoServices.currentVersion
import play.api.libs.json._
import play.api.libs.json.Json._
import com.keepit.common.db.ExternalId
import com.keepit.search.ArticleSearchResultRef

trait EventIdentifier {
  val name: String
  override def toString = name
}

case class UserEventIdentifier(name: String) extends EventIdentifier
case class ServerEventIdentifier(name: String) extends EventIdentifier

object EventFamily {
  // User
  val SLIDER = UserEventIdentifier("slider")
  val SEARCH = UserEventIdentifier("search")
  val SERVER = ServerEventIdentifier("server")
}

case class Meta[T](data: Tuple2[String,T]) {
  private def jsonSerializer: JsValue = {
    data._2 match {
      case s: ExternalId[_] => JsString(s.id)
      case s: Id[_] => JsNumber(s.id)
      case s: String => JsString(s)
      case _ => throw new Exception("")
    }
  }
  def toJson = JsObject(Seq(data._1 -> jsonSerializer))
}

case class MetaList[T](meta: Meta[_]*) {

  lazy val toJson = JsObject(meta.map(m => (m.data._1, Json.toJson(m.data._2))))
}

case class Event(eventType: EventIdentifier, metaData: Meta[_]*) {
  val createdAt: DateTime = currentDateTime
  lazy val serverVersion = currentService + ":" + currentVersion
  lazy val toJson = JsObject(
    Seq(
     ("createdAt", Json.toJson(createdAt.toString())),
     ("serverVersion", Json.toJson(serverVersion))
    )
  )
}


object Events {
  import EventIdentifiers.User._

  def pluginStart(userId: ExternalId[User]) = Event(PLUGIN_START, Meta(("userId" -> userId)))
  def initSearch(userId: ExternalId[User], searchUuid: ExternalId[ArticleSearchResultRef]) = Event(INIT_SEARCH, Meta(("searchUuid", searchUuid)))
  def sawResults(userId: ExternalId[User], searchUuid: ExternalId[ArticleSearchResultRef]) = Event(SAW_RESULTS, Meta(("searchUuid", searchUuid)))
  def clickedKifiResult(userId: ExternalId[User], searchUuid: ExternalId[ArticleSearchResultRef]) = Event(SAW_RESULTS, Meta(("searchUuid", searchUuid)))
}


class EventLog {

}