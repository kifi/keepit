package com.keepit.model

import com.keepit.common.db.{ Id, State, ModelWithState, States }
import com.keepit.common.json.EnumFormat
import com.keepit.common.net.{ URI, URIParser }
import com.keepit.common.reflection.Enumerator
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
  implicit val format: Format[LibrarySubscription] = (
    (__ \ 'id).formatNullable(Id.format[LibrarySubscription]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[LibrarySubscription]] and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'trigger).format[SubscriptionTrigger] and
    (__ \ 'info).format[SubscriptionInfo]
  )(LibrarySubscription.apply, unlift(LibrarySubscription.unapply))

  def toSubKey(sub: LibrarySubscription): LibrarySubscriptionKey = {
    LibrarySubscriptionKey(name = sub.name, info = sub.info, sub.state == LibrarySubscriptionStates.DISABLED)
  }
}

object LibrarySubscriptionStates extends States[LibrarySubscription] {
  val DISABLED = State[LibrarySubscription]("disabled")
}

sealed abstract class SubscriptionTrigger(val value: String)
object SubscriptionTrigger extends Enumerator[SubscriptionTrigger] {
  case object NEW_KEEP extends SubscriptionTrigger("new_keep")
  case object NEW_MEMBER extends SubscriptionTrigger("new_member")

  def all = _all.toSet
  def get(str: String) = all.find(_.value == str)
  def apply(str: String): SubscriptionTrigger = get(str).getOrElse(throw new Exception(s"Unknown SubscriptionTrigger $str"))
  implicit val format: Format[SubscriptionTrigger] = Format(
    EnumFormat.reads(get, all.map(_.value)),
    Writes { o => JsString(o.value) }
  )
}

trait SubscriptionInfo {
  def hasSameEndpoint(other: SubscriptionInfo): Boolean
}

case class SlackInfo(url: String) extends SubscriptionInfo {
  def hasSameEndpoint(other: SubscriptionInfo) = {
    other match {
      case SlackInfo(otherUrl) =>
        val uri1 = URIParser.parse(URIParser.uri, url).getOrElse("1").toString
        val uri2 = URIParser.parse(URIParser.uri, otherUrl).getOrElse("2").toString
        uri1 == uri2
      case _ => false
    }
  }
}

object SlackInfo {
  implicit val format: Format[SlackInfo] = Format(
    Reads { json =>
      (json \ "url").asOpt[String].filter(url => URIParser.parseAll(URIParser.uri, url).successful) match {
        case Some(url) => JsSuccess[SlackInfo](SlackInfo(url))
        case _ => JsError("[SlackInfo] format.reads: url not found")
      }
    },
    Writes { o => Json.obj("kind" -> "slack", "url" -> o.url) }
  )
}

object SubscriptionInfo {
  implicit val format: Format[SubscriptionInfo] = Format(
    Reads { json =>
      val kind = (json \ "kind").asOpt[String]
      kind match {
        case Some("slack") => Json.fromJson[SlackInfo](json)
        case Some(unknownKind) => JsError(s"[SubscriptionInfo] format.reads: unsupported_kind: $unknownKind")
        case _ => JsError("[SubscriptionInfo] format.reads: kind not found")
      }
    },
    Writes {
      case s: SlackInfo => SlackInfo.format.writes(s)
      case _ => throw new Exception("[SubscriptionInfo] format.writes: unsupported SubscriptionInfo")
    }
  )
}
