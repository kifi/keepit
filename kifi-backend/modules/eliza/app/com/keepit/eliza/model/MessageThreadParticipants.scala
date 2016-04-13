package com.keepit.eliza.model

import com.keepit.common.db._
import com.keepit.common.time.{ DateTimeJsonFormat, _ }
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.hashing.MurmurHash3

class MessageThreadParticipants(val userParticipants: Map[Id[User], DateTime], val nonUserParticipants: Map[NonUserParticipant, DateTime]) {
  def contains(user: Id[User]): Boolean = userParticipants.contains(user)
  def contains(nonUser: NonUserParticipant): Boolean = nonUserParticipants.contains(nonUser)
  def allUsersExcept(user: Id[User]): Set[Id[User]] = userParticipants.keySet - user

  lazy val size = userParticipants.size + nonUserParticipants.size
  lazy val allUsers = userParticipants.keySet
  lazy val allNonUsers = nonUserParticipants.keySet
  lazy val allEmails = allNonUsers.collect { case NonUserEmailParticipant(address) => address }
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

