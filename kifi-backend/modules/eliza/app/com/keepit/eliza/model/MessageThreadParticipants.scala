package com.keepit.eliza.model

import com.keepit.common.db._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.{ DateTimeJsonFormat, _ }
import com.keepit.common.util.DeltaSet
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.hashing.MurmurHash3

case class MessageThreadParticipants(userParticipants: Map[Id[User], DateTime], emailParticipants: Map[EmailParticipant, DateTime]) {
  def contains(user: Id[User]): Boolean = userParticipants.contains(user)
  def contains(nonUser: EmailParticipant): Boolean = emailParticipants.contains(nonUser)

  // Note that `diffed` is written such that adding/removing is idempotent. Removing is obvious,
  // but idempotent addition relies on the right-biased ++ operator on Maps
  def diffed(users: DeltaSet[Id[User]], emails: DeltaSet[EmailAddress]) = this.copy(
    userParticipants = users.added.map(_ -> currentDateTime).toMap ++ userParticipants -- users.removed,
    emailParticipants = emails.added.map(e => EmailParticipant(e) -> currentDateTime).toMap ++ emailParticipants -- emails.removed.map(EmailParticipant(_))
  )
  def plusUsers(users: Set[Id[User]]) = this.diffed(users = DeltaSet.addOnly(users), emails = DeltaSet.empty)
  def plusUser(user: Id[User]) = this.plusUsers(Set(user))
  def plusEmails(emails: Set[EmailAddress]) = this.diffed(users = DeltaSet.empty, emails = DeltaSet.addOnly(emails))
  def minusUsers(users: Set[Id[User]]) = this.diffed(users = DeltaSet.removeOnly(users), emails = DeltaSet.empty)
  def minusEmails(emails: Set[EmailAddress]) = this.diffed(users = DeltaSet.empty, emails = DeltaSet.removeOnly(emails))

  lazy val size = userParticipants.size + emailParticipants.size
  lazy val allUsers = userParticipants.keySet
  lazy val allNonUsers = emailParticipants.keySet
  lazy val allEmails = allNonUsers.map(_.address)
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
  val empty: MessageThreadParticipants = MessageThreadParticipants(Map.empty, Map.empty)
  implicit val format = new Format[MessageThreadParticipants] {
    def reads(json: JsValue) = {
      json match {
        case obj: JsObject =>
          (obj \ "us").asOpt[JsObject] match {
            case Some(users) =>
              val userParticipants = users.value.map {
                case (uid, timestamp) => (Id[User](uid.toLong), timestamp.as[DateTime])
              }.toMap
              (obj \ "nus").asOpt[JsArray].map { nonUsers =>
                nonUsers.value.flatMap(_.asOpt[JsArray]).flatMap { v =>
                  (v(0).asOpt[EmailParticipant], v(1).asOpt[DateTime]) match {
                    case (Some(_n), Some(_d)) => Some(_n -> _d)
                    case _ => None
                  }
                }.toMap
              } match {
                case Some(nonUserParticipants) =>
                  JsSuccess(MessageThreadParticipants(userParticipants, nonUserParticipants))
                case None =>
                  JsSuccess(MessageThreadParticipants(userParticipants, Map.empty[EmailParticipant, DateTime]))
              }
            case None =>
              // Old serialization format. No worries.
              val mtps = obj.value.map {
                case (uid, timestamp) => (Id[User](uid.toLong), timestamp.as[DateTime])
              }.toMap
              JsSuccess(MessageThreadParticipants(mtps, Map.empty[EmailParticipant, DateTime]))
          }
        case _ => JsError()
      }
    }

    def writes(mtps: MessageThreadParticipants): JsValue = {
      Json.obj(
        "us" -> mtps.userParticipants.map {
          case (uid, timestamp) => uid.id.toString -> Json.toJson(timestamp)
        },
        "nus" -> mtps.emailParticipants.toSeq.map {
          case (nup, timestamp) => JsArray(Seq(Json.toJson(nup), Json.toJson(timestamp)))
        }
      )
    }
  }

  def fromSets(users: Set[Id[User]], emails: Set[EmailParticipant] = Set.empty): MessageThreadParticipants = {
    MessageThreadParticipants(users.map(_ -> currentDateTime).toMap, emails.map(_ -> currentDateTime).toMap)
  }
}

