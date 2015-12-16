package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.{ Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.model.{ Keep, DeepLocator, User, NormalizedURI }
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.time.{ DateTimeJsonFormat }

import scala.util.hashing.MurmurHash3
import scala.concurrent.duration.Duration
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin }
import com.keepit.common.strings.StringWithNoLineBreaks

class MessageThreadParticipants(val userParticipants: Map[Id[User], DateTime], val nonUserParticipants: Map[NonUserParticipant, DateTime]) {

  def contains(user: Id[User]): Boolean = userParticipants.contains(user)
  def contains(nonUser: NonUserParticipant): Boolean = nonUserParticipants.contains(nonUser)
  def allUsersExcept(user: Id[User]): Set[Id[User]] = userParticipants.keySet - user

  lazy val allUsers = userParticipants.keySet
  lazy val allNonUsers = nonUserParticipants.keySet
  lazy val userHash: Int = MurmurHash3.setHash(allUsers)
  lazy val nonUserHash: Int = MurmurHash3.setHash(allNonUsers)
  lazy val hash = if (allNonUsers.isEmpty) userHash else nonUserHash + userHash

  override def equals(other: Any): Boolean = other match {
    case mtps: MessageThreadParticipants => super.equals(other) || (mtps.allUsers == allUsers && mtps.allNonUsers == allNonUsers)
    case _ => false
  }

  override def hashCode = allUsers.hashCode + allNonUsers.hashCode

  override def toString() = {
    s"MessageThreadPartitipant[users=${allUsers.mkString(",")}; nonusers=${allNonUsers.mkString(", ")}}]"
  }

}

object MessageThreadParticipants {
  val empty: MessageThreadParticipants = MessageThreadParticipants(Set.empty[Id[User]], Set.empty[NonUserParticipant])
  implicit val format = new Format[MessageThreadParticipants] {
    def reads(json: JsValue) = {
      json match {
        case obj: JsObject => {
          (obj \ "us").asOpt[JsObject] match {
            case Some(users) =>
              val userParticipants = users.value.map {
                case (uid, timestamp) => (Id[User](uid.toLong), timestamp.as[DateTime])
              }.toMap
              (obj \ "nus").asOpt[JsArray].map { nonUsers =>
                nonUsers.value.flatMap(_.asOpt[JsArray]).flatMap { v =>
                  (v(0).asOpt[NonUserParticipant], v(1).asOpt[DateTime]) match {
                    case (Some(_n), Some(_d)) => Some(_n -> _d)
                    case _ => None
                  }
                }.toMap
              } match {
                case Some(nonUserParticipants) =>
                  JsSuccess(MessageThreadParticipants(userParticipants, nonUserParticipants))
                case None =>
                  JsSuccess(MessageThreadParticipants(userParticipants, Map.empty[NonUserParticipant, DateTime]))
              }
            case None =>
              // Old serialization format. No worries.
              val mtps = obj.value.map {
                case (uid, timestamp) => (Id[User](uid.toLong), timestamp.as[DateTime])
              }.toMap
              JsSuccess(MessageThreadParticipants(mtps, Map.empty[NonUserParticipant, DateTime]))
          }
        }
        case _ => JsError()
      }
    }

    def writes(mtps: MessageThreadParticipants): JsValue = {
      Json.obj(
        "us" -> mtps.userParticipants.map {
          case (uid, timestamp) => uid.id.toString -> Json.toJson(timestamp)
        },
        "nus" -> mtps.nonUserParticipants.toSeq.map {
          case (nup, timestamp) => JsArray(Seq(Json.toJson(nup), Json.toJson(timestamp)))
        }
      )
    }
  }

  def apply(initialUserParticipants: Set[Id[User]]): MessageThreadParticipants = {
    new MessageThreadParticipants(initialUserParticipants.map { userId => (userId, currentDateTime) }.toMap, Map.empty[NonUserParticipant, DateTime])
  }

  def apply(initialUserParticipants: Set[Id[User]], initialNonUserPartipants: Set[NonUserParticipant]): MessageThreadParticipants = {
    new MessageThreadParticipants(initialUserParticipants.map { userId => (userId, currentDateTime) }.toMap, initialNonUserPartipants.map { nup => (nup, currentDateTime) }.toMap)
  }

  def apply(userParticipants: Map[Id[User], DateTime], nonUserParticipants: Map[NonUserParticipant, DateTime]): MessageThreadParticipants = {
    new MessageThreadParticipants(userParticipants, nonUserParticipants)
  }
}

case class MessageThread(
  id: Option[Id[MessageThread]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[MessageThread] = MessageThreadStates.ACTIVE,
  externalId: ExternalId[MessageThread] = ExternalId(),
  uriId: Id[NormalizedURI],
  url: String,
  nUrl: String,
  startedBy: Id[User],
  participants: MessageThreadParticipants,
  pageTitle: Option[String],
  keepId: Option[Id[Keep]] = None)
    extends ModelWithExternalId[MessageThread] {
  def participantsHash: Int = participants.hash
  def threadId: MessageThreadId = MessageThreadId(keepId, externalId)
  def deepLocator(implicit publicIdConfig: PublicIdConfiguration): DeepLocator = MessageThreadId.toLocator(threadId)

  def clean(): MessageThread = copy(pageTitle = pageTitle.map(_.trimAndRemoveLineBreaks()))

  def withId(id: Id[MessageThread]): MessageThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def withStartedBy(owner: Id[User]) = if (participants.contains(owner)) {
    this.copy(startedBy = owner)
  } else {
    this.withParticipants(currentDateTime, Set(owner)).copy(startedBy = owner)
  }
  def withKeepId(keepId: Id[Keep]): MessageThread = this.copy(keepId = Some(keepId))

  def withParticipants(when: DateTime, userIds: Set[Id[User]], nonUsers: Set[NonUserParticipant] = Set.empty) = {
    val newUsers = userIds.map(_ -> when).toMap
    val newNonUsers = nonUsers.map(_ -> when).toMap
    val newParticipants = MessageThreadParticipants(participants.userParticipants ++ newUsers, participants.nonUserParticipants ++ newNonUsers)
    this.copy(participants = newParticipants)
  }
  def withoutParticipant(userId: Id[User]) = {
    val newParticpiants = MessageThreadParticipants(participants.userParticipants - userId, participants.nonUserParticipants)
    this.copy(participants = newParticpiants)
  }

  def containsUser(user: Id[User]): Boolean = participants.contains(user)
  def containsNonUser(nonUser: NonUserParticipant): Boolean = participants.contains(nonUser)
  def allParticipantsExcept(user: Id[User]): Set[Id[User]] = participants.allUsersExcept(user)
  def allParticipants: Set[Id[User]] = participants.allUsers
}

object MessageThreadStates extends States[MessageThread]

object MessageThread {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[MessageThread]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[MessageThread]] and
    (__ \ 'externalId).format[ExternalId[MessageThread]] and
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'url).format[String] and
    (__ \ 'nUrl).format[String] and
    (__ \ 'startedBy).format[Id[User]] and
    (__ \ 'participants).format[MessageThreadParticipants] and
    (__ \ 'pageTitle).formatNullable[String] and
    (__ \ 'keep).formatNullable[Id[Keep]]
  )(MessageThread.apply, unlift(MessageThread.unapply))
}

sealed trait MessageThreadId
case class ThreadExternalId(threadId: ExternalId[MessageThread]) extends MessageThreadId
case class KeepId(keepId: Id[Keep]) extends MessageThreadId
object MessageThreadId {
  def toIdString(id: MessageThreadId)(implicit publicIdConfiguration: PublicIdConfiguration): String = id match {
    case ThreadExternalId(threadId) => threadId.id
    case KeepId(keepId) => Keep.publicId(keepId).id
  }

  def fromIdString(idStr: String)(implicit publicIdConfiguration: PublicIdConfiguration): Option[MessageThreadId] = {
    ExternalId.asOpt[MessageThread](idStr).map(ThreadExternalId(_)) orElse Keep.validatePublicId(idStr).flatMap(pubId => Keep.decodePublicId(pubId).map(KeepId(_)).toOption)
  }

  implicit def format(implicit publicIdConfiguration: PublicIdConfiguration) = Format[MessageThreadId](
    Reads(value => value.validate[String].flatMap(fromIdString(_).map(JsSuccess(_)) getOrElse JsError(s"Invalid MessageThreadId: $value"))),
    Writes(id => JsString(toIdString(id)))
  )

  def toLocator(id: MessageThreadId)(implicit publicIdConfiguration: PublicIdConfiguration): DeepLocator = DeepLocator(s"/messages/${toIdString(id)}")

  def apply(keepId: Option[Id[Keep]], externalId: ExternalId[MessageThread]): MessageThreadId = keepId.map(KeepId) getOrElse ThreadExternalId(externalId)
}

case class MessageThreadExternalIdKey(externalId: ExternalId[MessageThread]) extends Key[MessageThread] {
  override val version = 8
  val namespace = "message_thread_by_external_id"
  def toKey(): String = externalId.id
}

class MessageThreadExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessageThreadExternalIdKey, MessageThread](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

