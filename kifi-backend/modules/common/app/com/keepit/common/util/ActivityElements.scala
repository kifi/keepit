package com.keepit.common.util

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.path.Path
import com.keepit.common.reflection.Enumerator
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.core._
import com.keepit.discussion.Message
import com.keepit.model.{ Organization, Library, User, BasicOrganization, LibraryColor, BasicLibrary }
import com.keepit.social.BasicAuthor.{ TwitterUser, SlackUser, KifiUser }
import com.keepit.social.{ BasicAuthor, BasicUser, BasicNonUser }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class ActivityKind(val value: String)
object ActivityKind extends Enumerator[ActivityKind] {
  case object Initial extends ActivityKind("initial")
  case object Comment extends ActivityKind("comment")
  case object AddedParticipants extends ActivityKind("added_participants")
  case object AddedLibrary extends ActivityKind("added_library")
  case object EditedTitle extends ActivityKind("edited_title")

  val all = _all
  def apply(str: String) = all.find(_.value == str).get

  implicit val writes: Writes[ActivityKind] = Writes { o => JsString(o.value) }
}

sealed abstract class ActivitySource(val value: String)
object ActivitySource extends Enumerator[ActivitySource] {
  case object Slack extends ActivitySource("Slack")
  case object Twitter extends ActivitySource("Twitter")
  case object iOS extends ActivitySource("iOS")
  case object Android extends ActivitySource("Android")
  case object Chrome extends ActivitySource("Chrome")
  case object Firefox extends ActivitySource("Firefox")
  case object Safari extends ActivitySource("Safari")

  val all = _all
  def apply(str: String) = all.find(_.value == str).get

  implicit val writes: Writes[ActivitySource] = Writes { o => JsString(o.value) }
}

case class ActivityEvent(
  kind: ActivityKind,
  image: String,
  header: DescriptionElements, // e.g. "Cam kept this in LibraryX"
  body: DescriptionElements, // message and keep.note content
  timestamp: DateTime,
  source: Option[ActivitySource])
object ActivityEvent {
  def fromComment(msg: Message)(implicit iamgeConfig: S3ImageConfig): ActivityEvent = {
    import com.keepit.common.util.DescriptionElements._
    val msgAuthor = msg.sentBy.fold(fromNonUser, fromBasicUser)
    ActivityEvent(
      ActivityKind.Comment,
      image = msg.sentBy.right.toOption.map(_.picturePath.getUrl).getOrElse("0.jpg"),
      header = DescriptionElements(msgAuthor, "commented on this page"),
      body = DescriptionElements(msg.text),
      timestamp = msg.sentAt,
      source = None // todo(cam): add eliza.message.source (column) to Message
    )
  }

  implicit val writes: Writes[ActivityEvent] = (
    (__ \ 'kind).write[ActivityKind] and
    (__ \ 'image).write[String] and
    (__ \ 'header).write[DescriptionElements] and
    (__ \ 'body).write[DescriptionElements] and
    (__ \ 'timestamp).write[DateTime] and
    (__ \ 'source).writeNullable[ActivitySource]
  )(unlift(ActivityEvent.unapply))
}

case class ActivityLog(events: Seq[ActivityEvent], numEvents: Int, numComments: Int)
object ActivityLog {
  val empty = ActivityLog(Seq.empty, numEvents = 0, numComments = 0)

  implicit val writes = new Writes[ActivityLog] {
    def writes(o: ActivityLog) = Json.obj("events" -> o.events, "numEvents" -> o.numEvents, "numComments" -> o.numComments)
  }
}
