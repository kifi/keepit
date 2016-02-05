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
    uriId: Id[NormalizedURI],
    url: String,
    nUrl: String,
    startedBy: Id[User],
    participants: MessageThreadParticipants,
    pageTitle: Option[String],
    keepId: Id[Keep]) extends Model[MessageThread] with ModelWithState[MessageThread] {
  def participantsHash: Int = participants.hash
  def pubKeepId(implicit publicIdConfig: PublicIdConfiguration): PublicId[Keep] = Keep.publicId(keepId)
  def deepLocator(implicit publicIdConfig: PublicIdConfiguration): DeepLocator = MessageThread.locator(pubKeepId)

  def clean(): MessageThread = copy(pageTitle = pageTitle.map(_.trimAndRemoveLineBreaks()))

  def withId(id: Id[MessageThread]): MessageThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def withStartedBy(owner: Id[User]) = if (participants.contains(owner)) {
    this.copy(startedBy = owner)
  } else {
    this.withParticipants(currentDateTime, Set(owner)).copy(startedBy = owner)
  }
  def withKeepId(newKeepId: Id[Keep]): MessageThread = this.copy(keepId = newKeepId)

  def withParticipants(participants: MessageThreadParticipants) = this.copy(participants = participants)
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
    (__ \ 'keep).format[Id[Keep]]
  )(MessageThread.apply, unlift(MessageThread.unapply))

  def locator(keepId: PublicId[Keep]): DeepLocator = DeepLocator(s"/messages/${keepId.id}")
}

case class MessageThreadKeepIdKey(keepId: Id[Keep]) extends Key[MessageThread] {
  override val version = 2
  val namespace = "message_thread_by_keep_id"
  def toKey(): String = keepId.id.toString
}

class MessageThreadKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessageThreadKeepIdKey, MessageThread](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

