package com.keepit.eliza.model

import com.keepit.common.db._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.{ DateTimeJsonFormat, _ }
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.hashing.MurmurHash3

case class MessageThreadParticipants(userParticipants: Map[Id[User], DateTime], emailParticipants: Map[EmailParticipant, DateTime]) {
  def contains(user: Id[User]): Boolean = userParticipants.contains(user)
  def contains(nonUser: EmailParticipant): Boolean = emailParticipants.contains(nonUser)

  def plusUsers(users: Set[Id[User]]) = this.copy(userParticipants = userParticipants -- users)
  def plusEmails(emails: Set[EmailAddress]) = this.copy(emailParticipants = emailParticipants -- emails.map(EmailParticipant(_)))
  def minusUsers(users: Set[Id[User]]) = this.copy(userParticipants = userParticipants -- users)
  def minusEmails(emails: Set[EmailAddress]) = this.copy(emailParticipants = emailParticipants -- emails.map(EmailParticipant(_)))

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

