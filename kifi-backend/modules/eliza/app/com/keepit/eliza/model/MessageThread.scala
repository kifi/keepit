package com.keepit.eliza.model

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.strings.StringWithNoLineBreaks
import com.keepit.common.time.{ DateTimeJsonFormat, _ }
import com.keepit.model.{ DeepLocator, Keep, NormalizedURI, User }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class MessageThread(
  id: Option[Id[MessageThread]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[MessageThread] = MessageThreadStates.ACTIVE,
  uriId: Id[NormalizedURI],
  url: String,
  nUrl: String,
  startedBy: Id[User],
  participants: MessageThreadParticipants,
  pageTitle: Option[String],
  keepId: Id[Keep],
  numMessages: Int)
    extends Model[MessageThread] with ModelWithState[MessageThread] {
  def pubKeepId(implicit publicIdConfig: PublicIdConfiguration): PublicId[Keep] = Keep.publicId(keepId)
  def deepLocator(implicit publicIdConfig: PublicIdConfiguration): DeepLocator = MessageThread.locator(pubKeepId)

  def clean(): MessageThread = this.copy(pageTitle = pageTitle.map(_.trimAndRemoveLineBreaks))

  def withId(id: Id[MessageThread]): MessageThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def withStartedBy(owner: Id[User]) = this.copy(
    startedBy = owner,
    participants = participants.plusUser(owner)
  )
  def withKeepId(newKeepId: Id[Keep]): MessageThread = this.copy(keepId = newKeepId)
  def withUriId(uriId: Id[NormalizedURI]): MessageThread = this.copy(uriId = uriId)
  def withParticipants(participants: MessageThreadParticipants) = this.copy(participants = participants)
  def withNumMessages(num: Int) = this.copy(numMessages = num)

  def sanitizeForDelete = this.copy(state = MessageThreadStates.INACTIVE)
}

object MessageThreadStates extends States[MessageThread]

object MessageThread {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[MessageThread]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[MessageThread]] and
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'url).format[String] and
    (__ \ 'nUrl).format[String] and
    (__ \ 'startedBy).format[Id[User]] and
    (__ \ 'participants).format[MessageThreadParticipants] and
    (__ \ 'pageTitle).formatNullable[String] and
    (__ \ 'keep).format[Id[Keep]] and
    (__ \ 'numMessages).formatNullable[Int].inmap[Int](_ getOrElse 0, Some(_))
  )(MessageThread.apply, unlift(MessageThread.unapply))

  def locator(keepId: PublicId[Keep]): DeepLocator = DeepLocator(s"/messages/${keepId.id}")
}

case class MessageThreadKeepIdKey(keepId: Id[Keep]) extends Key[MessageThread] {
  override val version = 4
  val namespace = "message_thread_by_keep_id"
  def toKey(): String = keepId.id.toString
}

class MessageThreadKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessageThreadKeepIdKey, MessageThread](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

