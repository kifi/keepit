package com.keepit.model

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json._

case class TwitterId(id: Long) // https://groups.google.com/forum/#!topic/twitter-development-talk/ahbvo3VTIYI

object TwitterId {
  implicit def format = Json.format[TwitterId]
}

case class KeepSourceAttribution(
    id: Option[Id[KeepSourceAttribution]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    attribution: SourceAttribution,
    state: State[KeepSourceAttribution] = KeepSourceAttributionStates.ACTIVE) extends ModelWithState[KeepSourceAttribution] {

  def withId(id: Id[KeepSourceAttribution]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object KeepSourceAttribution {
  import com.keepit.model.KeepAttributionType._

  implicit val writes = new Writes[KeepSourceAttribution] {
    def writes(x: KeepSourceAttribution): JsValue = {
      val (attrType, attrJs) = toJsValue(x.attribution)
      Json.obj(attrType.name -> attrJs)
    }
  }

  private def toJsValue(attr: SourceAttribution): (KeepAttributionType, JsValue) = {
    attr match {
      case x: TwitterAttribution => (Twitter, TwitterAttribution.format.writes(x))
    }
  }

  private def fromJsValue(attrType: KeepAttributionType, attrJson: JsValue): SourceAttribution = {
    attrType match {
      case Twitter => TwitterAttribution.format.reads(attrJson).get
      case x => throw new UnknownAttributionTypeException(x.name)
    }
  }

  def unapplyToDbRow(attr: KeepSourceAttribution) = {
    val (attrType, js) = toJsValue(attr.attribution)
    Some((attr.id, attr.createdAt, attr.updatedAt, attrType, js, attr.state))
  }

  def applyFromDbRow(id: Option[Id[KeepSourceAttribution]], createdAt: DateTime, updatedAt: DateTime, attrType: KeepAttributionType, attrJson: JsValue, state: State[KeepSourceAttribution]) = {
    val attr = fromJsValue(attrType, attrJson)
    KeepSourceAttribution(id, createdAt, updatedAt, attr, state)
  }

}

case class UnknownAttributionTypeException(msg: String) extends Exception(msg)

object KeepSourceAttributionStates extends States[KeepSourceAttribution]

case class KeepAttributionType(name: String)

object KeepAttributionType {
  val Twitter = KeepAttributionType("twitter")
}

sealed trait SourceAttribution

case class TwitterAttribution(id: TwitterId, screenName: String) extends SourceAttribution {
  def getOriginalURL: String = s"https://twitter.com/$screenName/status/${id.id}"
  def getHandle: String = screenName
}

object TwitterAttribution {
  implicit val idFormat = TwitterId.format
  implicit val format = Json.format[TwitterAttribution]

  def fromRawTweetJson(js: JsValue): Option[TwitterAttribution] = {
    val idOpt = (js \ "id").asOpt[TwitterId]
    val screenNameOpt = (js \ "user" \ "screen_name").asOpt[String]
    for {
      id <- idOpt
      screenName <- screenNameOpt
    } yield TwitterAttribution(id, screenName)
  }
}
