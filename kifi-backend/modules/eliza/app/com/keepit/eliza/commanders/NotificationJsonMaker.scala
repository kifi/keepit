package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }

import play.api.libs.json.{ JsValue, Json, JsObject }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

private[commanders] case class NotificationJson(obj: JsObject) extends AnyVal

/** Makes `NotificationJson` from `RawNotification` */
private[commanders] class NotificationJsonMaker @Inject() (
    shoebox: ShoeboxServiceClient) {

  def make(rawNotification: RawNotification): Future[NotificationJson] = makeOpt(rawNotification).get

  def make(rawNotifications: Seq[RawNotification]): Future[Seq[NotificationJson]] = {
    Future.sequence(rawNotifications.map(makeOpt).flatten)
  }

  private def makeOpt(rawNotification: RawNotification): Option[Future[NotificationJson]] = {
    @inline def updates(o: JsObject) = {
      if (rawNotification._2) // unread
        Json.obj(
          "unread" -> true,
          "unreadMessages" -> math.max(1, (o \ "unreadMessages").asOpt[Int].getOrElse(0)),
          "unreadAuthors" -> math.max(1, (o \ "unreadAuthors").asOpt[Int].getOrElse(0)))
      else
        Json.obj(
          "unread" -> false,
          "unreadMessages" -> 0,
          "unreadAuthors" -> 0)
    }
    rawNotification._1 match {
      case o: JsObject => Some(updateSenderAndParticipants(o ++ updates(o)))
      case _ => None
    }
  }

  private def updateSenderAndParticipants(data: JsObject): Future[NotificationJson] = {
    val author: Option[BasicUserLikeEntity] = (data \ "author").asOpt[BasicUserLikeEntity]
    val participantsOpt: Option[Seq[BasicUserLikeEntity]] = (data \ "participants").asOpt[Seq[BasicUserLikeEntity]]
    participantsOpt.map { participants =>
      val updatedAuthorFuture: Future[Option[BasicUserLikeEntity]] =
        author.map { bule =>
          bule match {
            case bu: BasicUser => updateBasicUser(bu)
            case x => Future.successful(x)
          }
        } match {
          case None => Future.successful(None)
          case Some(fut) => fut.map(Some(_))
        }
      val updatedParticipantsFuture: Future[Seq[BasicUserLikeEntity]] = Future.sequence(participants.map { participant =>
        val updatedParticipant: Future[BasicUserLikeEntity] = participant match {
          case p: BasicUser => updateBasicUser(p)
          case _ => Future.successful(participant)
        }
        updatedParticipant
      })

      updatedParticipantsFuture.flatMap { updatedParticipants =>
        updatedAuthorFuture.map { updatedAuthor =>
          NotificationJson(data.deepMerge(Json.obj(
            "author" -> updatedAuthor,
            "participants" -> updatedParticipants
          )))
        }
      }
    } getOrElse {
      Future.successful(NotificationJson(data))
    }
  }

  private def updateBasicUser(basicUser: BasicUser): Future[BasicUser] = {
    shoebox.getUserOpt(basicUser.externalId) map { userOpt =>
      userOpt.map(BasicUser.fromUser).getOrElse(basicUser)
    } recover {
      case _ =>
        basicUser
    }
  }

}
