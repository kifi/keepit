package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.{ Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.{ ModelWithExternalId, Id, ExternalId }
import com.keepit.model.{ DeepLocator, User, NormalizedURI }
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
                nonUsers.value.map(_.asOpt[JsArray]).flatten.map { v =>
                  (v(0).asOpt[NonUserParticipant], v(1).asOpt[DateTime]) match {
                    case (Some(_n), Some(_d)) => Some(_n -> _d)
                    case _ => None
                  }
                }.flatten.toMap
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
  updateAt: DateTime = currentDateTime,
  externalId: ExternalId[MessageThread] = ExternalId(),
  uriId: Option[Id[NormalizedURI]],
  url: Option[String],
  nUrl: Option[String],
  pageTitle: Option[String],
  participants: Option[MessageThreadParticipants],
  participantsHash: Option[Int],
  replyable: Boolean)
    extends ModelWithExternalId[MessageThread] {
  def deepLocator: DeepLocator = DeepLocator(s"/messages/$externalId")

  def clean(): MessageThread = copy(pageTitle = pageTitle.map(_.trimAndRemoveLineBreaks()))

  def withId(id: Id[MessageThread]): MessageThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt = updateTime)

  def withParticipants(when: DateTime, userIds: Seq[Id[User]], nonUsers: Seq[NonUserParticipant] = Seq.empty) = {
    val newUsers = userIds.map(_ -> when).toMap
    val newNonUsers = nonUsers.map(_ -> when).toMap
    val newParticpiants = participants.map(ps => MessageThreadParticipants(ps.userParticipants ++ newUsers, ps.nonUserParticipants ++ newNonUsers))
    this.copy(participants = newParticpiants, participantsHash = newParticpiants.map(_.hash))
  }

  def withoutParticipant(userId: Id[User]) = {
    val newParticpiants = participants.map(ps => MessageThreadParticipants(ps.userParticipants - userId, ps.nonUserParticipants))
    this.copy(participants = newParticpiants, participantsHash = newParticpiants.map(_.hash))
  }

  def containsUser(user: Id[User]): Boolean = participants.exists(_.contains(user))
  def containsNonUser(nonUser: NonUserParticipant): Boolean = participants.exists(_.contains(nonUser))
  def allParticipantsExcept(user: Id[User]): Set[Id[User]] = participants.map(_.allUsersExcept(user)).getOrElse(Set[Id[User]]()) //Todo: add in nonuser participants?
  def allParticipants: Set[Id[User]] = participants.map(_.allUsers).getOrElse(Set[Id[User]]())
}

object MessageThread {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[MessageThread]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'externalId).format(ExternalId.format[MessageThread]) and
    (__ \ 'uriId).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'nUrl).formatNullable[String] and
    (__ \ 'pageTitle).formatNullable[String] and
    (__ \ 'participants).formatNullable[MessageThreadParticipants] and
    (__ \ 'participantsHash).formatNullable[Int] and
    (__ \ 'replyable).format[Boolean]
  )(MessageThread.apply, unlift(MessageThread.unapply))
}

case class MessageThreadExternalIdKey(externalId: ExternalId[MessageThread]) extends Key[MessageThread] {
  override val version = 3
  val namespace = "message_thread_by_external_id"
  def toKey(): String = externalId.id
}

class MessageThreadExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessageThreadExternalIdKey, MessageThread](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

