package com.keepit.eliza.commanders

import play.api.libs.json.{JsValue, Json, JsObject}
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject
import com.keepit.social.{BasicUserLikeEntity, BasicUser}
import scala.concurrent.{Promise, Future}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class RawNotifications(private[commanders] val jsonList: List[(play.api.libs.json.JsValue, Boolean)])

private[commanders] case class Notifications(jsons: Seq[JsObject])

class NotificationUpdater @Inject() (
  shoebox: ShoeboxServiceClient) {

  def update(rawNotifications: RawNotifications): Future[Notifications] =
    updateSendableNotifications(rawNotifications.jsonList) map { jsons => Notifications(jsons) }

  private def updateBasicUser(basicUser: BasicUser): Future[BasicUser] = {
    shoebox.getUserOpt(basicUser.externalId) map { userOpt =>
      userOpt.map(BasicUser.fromUser).getOrElse(basicUser)
    } recover {
      case _:Throwable => basicUser
    }
  }

  private def updateSenderAndParticipants(data: JsObject): Future[JsObject] = {
    val author: Option[BasicUser] = (data \ "author").asOpt[BasicUser]
    val participantsOpt: Option[Seq[BasicUserLikeEntity]] = (data \ "participants").asOpt[Seq[BasicUserLikeEntity]]
    participantsOpt.map { participants =>
      val updatedAuthorFuture : Future[Option[BasicUser]] =
        author.map(updateBasicUser).map(fut=>fut.map(Some(_))).getOrElse(Promise.successful(None.asInstanceOf[Option[BasicUser]]).future)
      val updatedParticipantsFuture : Future[Seq[BasicUserLikeEntity]]= Future.sequence(participants.map{ participant =>
        val updatedParticipant: Future[BasicUserLikeEntity] = participant match {
          case p : BasicUser => updateBasicUser(p)
          case _ => Promise.successful(participant).future
        }
        updatedParticipant
      })

      updatedParticipantsFuture.flatMap{ updatedParticipants =>
        updatedAuthorFuture.map{ updatedAuthor =>
          data.deepMerge(Json.obj(
            "author" -> updatedAuthor,
            "participants" -> updatedParticipants
          ))
        }
      }
    } getOrElse {
      Promise.successful(data).future
    }
  }

  private def updateSendableNotification(data: JsValue, unread: Boolean): Option[Future[JsObject]] = {
    data match {
      case x: JsObject => Some(updateSenderAndParticipants(x.deepMerge(
        if (unread) Json.obj("unread" -> unread) else Json.obj("unread" -> unread, "unreadAuthors" -> 0)
      )))
      case _ => None
    }
  }

  private def updateSendableNotifications(rawNotifications: Seq[(JsValue, Boolean)]): Future[Seq[JsObject]] = {
    Future.sequence(rawNotifications.map { case (data, unread) =>
      updateSendableNotification(data, unread)
    }.filter(_.isDefined).map(_.get))
  }


}