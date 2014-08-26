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

  def addInteraction(uid: Id[User], src: Either[Id[User], EmailAddress], interaction: UserInteraction) = {
    val interactions = db.readOnlyMaster { implicit s =>
      userValueRepo.getValue(uid, UserValues.recentInteractions)
    }.as[List[JsObject]]
    val newJson = src match {
      case Left(id) => Json.obj("user" -> id, "action" -> interaction.value)
      case Right(email) => Json.obj("email" -> email.address, "action" -> interaction.value)
    }
    // append to head as most recent (will get the highest weight), remove from tail as least recent (lowest weight)
    val newInteractions = if (interactions.length + 1 > UserInteraction.maximumInteractions) {
      newJson :: interactions.take(interactions.length - 1)
    } else {
      newJson :: interactions
    }
    db.readWrite { implicit s =>
      userValueRepo.setValue(uid, UserValueName.RECENT_INTERACTION, Json.stringify(Json.toJson(newInteractions)))
    }
  }

  // given an index position in an array and weight of action, calculate score
  private def calcInteractionScore(idx: Int, action: String): Double = {
    val score = UserInteraction.getScoreForAction(action)
    (15 * Math.pow(idx + 1.5, -0.7) + 0.5) * score
  }

  def getInteractionScores(uid: Id[User]): Seq[InteractionInfo] = {
    db.readOnlyMaster { implicit s =>
      val arr = userValueRepo.getValue(uid, UserValues.recentInteractions).as[Seq[JsObject]]
      val scores = for ((obj, i) <- arr.zipWithIndex) yield {
        val action = (obj \ "action").as[String]
        val entity = (obj \ "user").asOpt[Id[User]] match {
          case Some(id) =>
            Left(id)
          case None =>
            Right(EmailAddress((obj \ "email").as[String]))
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

case class InteractionInfo(entity: Either[Id[User], EmailAddress], score: Double)
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

  def getScoreForAction(action: String) = {
    action match {
      case INVITE_KIFI.value => INVITE_KIFI.score
      case INVITE_LIBRARY.value => INVITE_LIBRARY.score
      case MESSAGE_USER.value => MESSAGE_USER.score
    }
  }
}
