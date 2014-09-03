package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import play.api.libs.json.{ Json, JsObject }

class UserInteractionCommander @Inject() (
    db: Database,
    userRepo: UserRepo,
    userValueRepo: UserValueRepo) {

  def addInteractions(uid: Id[User], recipients: Seq[(InteractionRecipient, UserInteraction)]): Unit = {
    val interactions = db.readOnlyMaster { implicit s =>
      userValueRepo.getValue(uid, UserValues.recentInteractions)
    }.as[List[JsObject]]

    val newJson = recipients collect {
      case (UserRecipient(id), interaction) => Json.obj("user" -> id, "action" -> interaction.value)
      case (EmailRecipient(email), interaction) => Json.obj("email" -> email.address, "action" -> interaction.value)
    }

    // append to head as most recent (will get the highest weight), remove from tail as least recent (lowest weight)
    val newInteractions = newJson ++ interactions.take(UserInteraction.maximumInteractions - 1)
    db.readWrite { implicit s =>
      userValueRepo.setValue(uid, UserValueName.RECENT_INTERACTION, Json.stringify(Json.toJson(newInteractions)))
    }
  }

  // given an index position in an array and weight of action, calculate score
  private def calcInteractionScore(idx: Int, action: String): Double = {
    val score = UserInteraction.getAction(action).score
    (15 * Math.pow(idx + 1.5, -0.7) + 0.5) * score
  }

  def getRecentInteractions(uid: Id[User]): Seq[InteractionInfo] = {
    db.readOnlyMaster { implicit s =>
      val arr = userValueRepo.getValue(uid, UserValues.recentInteractions).as[Seq[JsObject]]
      val scores = for ((obj, i) <- arr.zipWithIndex) yield {
        val action = (obj \ "action").as[String]
        val entity = (obj \ "user").asOpt[Id[User]] match {
          case Some(id) =>
            UserRecipient(id)
          case None =>
            EmailRecipient(EmailAddress((obj \ "email").as[String]))
        }
        (entity, calcInteractionScore(i, action))
      }
      scores.groupBy(e => e._1).map { b =>
        val sum = b._2.foldLeft(0.0)((r, c) => r + c._2)
        InteractionInfo(b._1, sum)
      }.toSeq.sorted.reverse
    }
  }
}

case class InteractionInfo(recipient: InteractionRecipient, score: Double)
object InteractionInfo {
  implicit def ord: Ordering[InteractionInfo] = new Ordering[InteractionInfo] {
    def compare(x: InteractionInfo, y: InteractionInfo): Int = x.score compare y.score
  }
}

sealed abstract class UserInteraction(val value: String, val score: Double)
object UserInteraction {
  case object INVITE_KIFI extends UserInteraction("invite_kifi", 1.0)
  case object INVITE_LIBRARY extends UserInteraction("invite_library", 1.0)
  case object MESSAGE_USER extends UserInteraction("message", 1.0)

  val maximumInteractions = 100

  def getAction(action: String) = {
    action match {
      case INVITE_KIFI.value => INVITE_KIFI
      case INVITE_LIBRARY.value => INVITE_LIBRARY
      case MESSAGE_USER.value => MESSAGE_USER
    }
  }
}

sealed trait InteractionRecipient
case class UserRecipient(id: Id[User]) extends InteractionRecipient
case class EmailRecipient(address: EmailAddress) extends InteractionRecipient
