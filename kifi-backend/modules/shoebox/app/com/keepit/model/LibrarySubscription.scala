package com.keepit.model

import com.keepit.common.db.{ Id, State, ModelWithState, States }
import com.keepit.common.net.URIParser
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class LibrarySubscription(
    id: Option[Id[LibrarySubscription]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibrarySubscription] = LibrarySubscriptionStates.ACTIVE,
    libraryId: Id[Library],
    name: String,
    trigger: SubscriptionTrigger,
    info: SubscriptionInfo) extends ModelWithState[LibrarySubscription] {
  def withId(id: Id[LibrarySubscription]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[LibrarySubscription]) = this.copy(state = state)
  def equivalent(that: LibrarySubscription) = this.name == that.name || (this.trigger == that.trigger && this.info.hasSameEndpoint(that.info))
}

object LibrarySubscription {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibrarySubscription]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[LibrarySubscription]] and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'trigger).format[SubscriptionTrigger] and
    (__ \ 'info).format[SubscriptionInfo])(LibrarySubscription.apply _, unlift(LibrarySubscription.unapply))
}

object LibrarySubscriptionStates extends States[LibrarySubscription] {
  val PAUSED = State[LibrarySubscription]("paused")
}

trait SubscriptionInfo {
  def hasSameEndpoint(other: SubscriptionInfo): Boolean
}

case class SlackInfo(url: String) extends SubscriptionInfo {
  def hasSameEndpoint(other: SubscriptionInfo) = {
    other match {
      case SlackInfo(otherUrl) => url == otherUrl
      case _ => false
    }
  }
}

object SlackInfo {
  implicit val format: Format[SlackInfo] = new Format[SlackInfo] {
    def reads(json: JsValue): JsResult[SlackInfo] = {
      (json \ "url").asOpt[String].filter(url => URIParser.parseAll(URIParser.uri, url).successful) match {
        case Some(url) => JsSuccess[SlackInfo](SlackInfo(url))
        case _ => JsError("[SlackInfo] format.reads: url not found")
      }
    }
    def writes(o: SlackInfo): JsValue = Json.obj("kind" -> "slack", "url" -> o.url)
  }

}

object SubscriptionInfo {
  implicit val format: Format[SubscriptionInfo] = new Format[SubscriptionInfo] {
    def reads(json: JsValue): JsResult[SubscriptionInfo] = {
      val kind = (json \ "kind").asOpt[String]
      kind match {
        case Some("slack") => Json.fromJson[SlackInfo](json)
        case Some(unknownKind) => JsError(s"[SubscriptionInfo] format.reads: unsupported_kind: $unknownKind")
        case _ => JsError("[SubscriptionInfo] format.reads: kind not found")
      }
    }
    def writes(subscription: SubscriptionInfo): JsValue = {
      subscription match {
        case s: SlackInfo => SlackInfo.format.writes(s)
        case _ => throw new Exception("[SubscriptionInfo] format.writes: unsupported SubscriptionInfo")
      }
    }
  }
}

case class SubscriptionTrigger(value: String)

object SubscriptionTrigger {
  val NEW_KEEP = SubscriptionTrigger("new_keep")
  val NEW_MEMBER = SubscriptionTrigger("new_member")

  implicit def format: Format[SubscriptionTrigger] = Format(
    __.read[String].map(SubscriptionTrigger(_)),
    new Writes[SubscriptionTrigger] { def writes(o: SubscriptionTrigger) = JsString(o.value) })
}

