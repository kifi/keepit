package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.kifi.macros.json
import org.apache.poi.ss.formula.functions.T
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.slick.collection.heterogenous.Zero.+
import scala.slick.lifted.MappedTo

case class LibrarySubscription(
    id: Option[Id[LibrarySubscription]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibrarySubscription] = State("active"),
    trigger: SubscriptionTrigger,
    libraryid: Id[Library],
    info: SubscriptionInfo) extends ModelWithState[LibrarySubscription] {
  def withId(id: Id[LibrarySubscription]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[LibrarySubscription]) = this.copy(state = state)
  def isActive = this.state.value == "active"
}

object LibrarySubscription {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibrarySubscription]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[LibrarySubscription]] and
    (__ \ 'trigger).format[SubscriptionTrigger] and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'info).format[SubscriptionInfo])(LibrarySubscription.apply _, unlift(LibrarySubscription.unapply)) // json de/serialization
}

trait SubscriptionInfo

case class SlackInfo(kind: String = "slack", url: String) extends SubscriptionInfo

object SlackInfo extends SubscriptionInfo {
  implicit def format: Format[SlackInfo] = (
    (__ \ "kind").format[String] and
    (__ \ "url").format[String])(SlackInfo.apply, unlift(SlackInfo.unapply))
}

object SubscriptionInfo {
  implicit val format: Format[SubscriptionInfo] = new Format[SubscriptionInfo] {
    def reads(json: JsValue): JsResult[SubscriptionInfo] = {
      val kind = (json \ "kind").asOpt[String]
      kind match {
        case Some("slack") => Json.fromJson[SlackInfo](json)
        case s: Some[String] => JsError("[LibrarySubscription] tried to read into SubscriptionInfo with invalid kind field")
        case _ => JsError("[LibrarySubscription] No kind field found, can't read into SubscriptionInfo")
      }
    }
    def writes(subscription: SubscriptionInfo): JsValue = {
      subscription match {
        case s: SlackInfo => Json.toJson(s)
        case _ => throw new Exception("[LibrarySubscription] Subscription type not supported")
      }
    }
  }
}

case class SubscriptionTrigger(value: String)

object SubscriptionTrigger {
  val NEW_KEEP = SubscriptionTrigger("new_keep")

  implicit def format: Format[SubscriptionTrigger] = Format(
    __.read[String].map(SubscriptionTrigger(_)),
    new Writes[SubscriptionTrigger] { def writes(o: SubscriptionTrigger) = JsString(o.value) })
}

