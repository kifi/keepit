package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.json.{ Json, JsObject }

import scala.collection.mutable

class UserInteractionCommander @Inject() (
    db: Database,
    userValueRepo: UserValueRepo) extends Logging {

  def addInteractions(uid: Id[User], recipients: Seq[(InteractionRecipient, UserInteraction)]): Unit = {
    val interactions = db.readOnlyMaster { implicit s =>
      userValueRepo.getValue(uid, UserValues.recentInteractions)
    }.as[List[JsObject]]

    val newJson = recipients collect {
      case (UserInteractionRecipient(id), interaction) => Json.obj("user" -> id, "action" -> interaction.value)
      case (EmailInteractionRecipient(email), interaction) => Json.obj("email" -> email.address, "action" -> interaction.value)
      case (LibraryInteraction(id), interaction) => Json.obj("library" -> id, "action" -> interaction.value)
    }

    val limited = {
      val existing = mutable.Map.empty[JsObject, Int].withDefaultValue(0)
      interactions.filter { int =>
        val cnt = existing(int)
        if (cnt < UserInteraction.maximumInteractionsPerRecipient) {
          existing.put(int, cnt + 1)
          true
        } else {
          false
        }
      }
    }

    // append to head as most recent (will get the highest weight), remove from tail as least recent (lowest weight)
    val newInteractions = newJson.reverse ++ limited.take(UserInteraction.maximumInteractions - 1)
    db.readWrite { implicit s =>
      userValueRepo.setValue(uid, UserValueName.RECENT_INTERACTION, Json.stringify(Json.toJson(newInteractions)))
    }
  }

  // given an index position in an array and weight of action, calculate score
  private def calcInteractionScore(idx: Int, action: UserInteraction): Double = {
    (15 * Math.pow(idx + 1.5, -0.7) + 0.3) * action.score
  }

  def parseJson(obj: JsObject): Option[(InteractionRecipient, UserInteraction)] = {
    val actionOpt: Option[UserInteraction] = (obj \ "action").asOpt[String].map(UserInteraction.getAction)
    val entityOpt: Option[InteractionRecipient] = (obj \ "user").asOpt[Id[User]].map(UserInteractionRecipient).orElse {
      (obj \ "email").asOpt[String].map(r => EmailInteractionRecipient(EmailAddress(r)))
    }.orElse {
      (obj \ "library").asOpt[Id[Library]].map(LibraryInteraction)
    }

    val res = for { e <- entityOpt; a <- actionOpt } yield (e, a)
    res.orElse {
      log.warn(s"[getRecentInteractions] Unknown interaction $obj")
      None
    }
  }

  def getRecentInteractions(uid: Id[User]): Seq[InteractionScore] = {
    val interactions = db.readOnlyMaster { implicit s =>
      userValueRepo.getValue(uid, UserValues.recentInteractions).as[Seq[JsObject]]
    }
    val scores = for ((obj, i) <- interactions.zipWithIndex) yield {
      parseJson(obj).map(r => (r._1, calcInteractionScore(i, r._2)))
    }

    val res = scores.flatten.groupBy(e => e._1).map { b =>
      val sum = b._2.foldLeft(0.0)((r, c) => r + c._2)
      InteractionScore(b._1, sum)
    }.toSeq.sorted.reverse
    
    res
  }

  def suggestFriendsAndContacts(userId: Id[User], limit: Option[Int]): (Seq[Id[User]], Seq[EmailAddress]) = {
    val allRecentInteractions = getRecentInteractions(userId)
    val relevantInteractions = limit.map(allRecentInteractions.take) getOrElse allRecentInteractions
    val split = grouped(relevantInteractions)

    (split.users, split.emails)
  }

  def grouped(interactions: Seq[InteractionScore]): GroupedInteractions = {
    val userIds = interactions.collect {
      case InteractionScore(UserInteractionRecipient(id), _) => id
    }
    val emailAddresses = interactions.collect {
      case InteractionScore(EmailInteractionRecipient(email), _) => email
    }
    val libraries = interactions.collect {
      case InteractionScore(LibraryInteraction(id), _) => id
    }
    GroupedInteractions(userIds, emailAddresses, libraries)
  }
}

case class InteractionScore(recipient: InteractionRecipient, score: Double)
object InteractionScore {
  implicit def ord: Ordering[InteractionScore] = new Ordering[InteractionScore] {
    def compare(x: InteractionScore, y: InteractionScore): Int = x.score compare y.score
  }
}

case class GroupedInteractions(users: Seq[Id[User]], emails: Seq[EmailAddress], libraries: Seq[Id[Library]])

sealed abstract class UserInteraction(val value: String, val score: Double)
object UserInteraction {
  case object INVITE_KIFI extends UserInteraction("invite_kifi", 1.0)
  case object INVITE_LIBRARY extends UserInteraction("invite_library", 1.0)
  case object MESSAGE_USER extends UserInteraction("message", 1.0)
  case object KEPT_TO_LIBRARY extends UserInteraction("keep_to_library", 2.0)

  val maximumInteractions = 250
  val maximumInteractionsPerRecipient = 25

  def getAction(action: String) = {
    action match {
      case INVITE_KIFI.value => INVITE_KIFI
      case INVITE_LIBRARY.value => INVITE_LIBRARY
      case MESSAGE_USER.value => MESSAGE_USER
      case KEPT_TO_LIBRARY.value => KEPT_TO_LIBRARY
    }
  }
}

sealed trait InteractionRecipient
case class UserInteractionRecipient(id: Id[User]) extends InteractionRecipient
case class EmailInteractionRecipient(address: EmailAddress) extends InteractionRecipient
case class LibraryInteraction(libraryId: Id[Library]) extends InteractionRecipient
