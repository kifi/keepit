package com.keepit.model

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json._

case class KeepSourceAttribution(
    id: Option[Id[KeepSourceAttribution]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    attributionType: KeepAttributionType,
    attributionJson: Option[JsValue],
    state: State[KeepSourceAttribution] = KeepSourceAttributionStates.ACTIVE) extends ModelWithState[KeepSourceAttribution] {

  import KeepAttributionType._

  def withId(id: Id[KeepSourceAttribution]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def parseAttribution(): Option[SourceAttribution] = {
    this.attributionType match {
      case Twitter => attributionJson.map { js => TwitterAttribution.format.reads(js).get }
      case _ => throw new Exception("unsupported keep attribution type")
    }
  }
}

object KeepSourceAttributionStates extends States[KeepSourceAttribution]

case class KeepAttributionType(name: String)

object KeepAttributionType {
  val Twitter = KeepAttributionType("twitter")
}

trait SourceAttribution

case class TwitterAttribution(idString: String, screenName: String) extends SourceAttribution {
  def getOriginalURL: String = s"https://twitter.com/$screenName/status/$idString"
  def getHandle: String = screenName
}

object TwitterAttribution {
  implicit val format = Json.format[TwitterAttribution]

  def fromRawTweetJson(js: JsValue): Option[TwitterAttribution] = {
    val idStringOpt = (js \ "id_str").asOpt[String]
    val screenNameOpt = (js \ "user" \ "screen_name").asOpt[String]
    for {
      idString <- idStringOpt
      screenName <- screenNameOpt
    } yield TwitterAttribution(idString, screenName)
  }
}
