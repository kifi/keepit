package com.keepit.eliza.commanders

import scala.concurrent.{Promise, Future}
import play.api.libs.json.{Json, JsArray, JsString}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model.User
import com.keepit.common.akka.SafeFuture
import com.keepit.social.BasicUser
import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MessagingUtils @Inject() (shoebox: ShoeboxServiceClient) extends Logging  {

  def modifyMessageWithAuxData(m: MessageWithBasicUser): Future[MessageWithBasicUser] = {
    if (m.user.isEmpty) {
      val modifiedMessage = m.auxData match {
        case Some(auxData) =>
          val auxModifiedFuture = auxData.value match {
            case JsString("add_participants") +: JsString(jsAdderUserId) +: JsArray(jsAddedUsers) +: _ =>
              val addedUsers = jsAddedUsers.map(id => Id[User](id.as[Long]))
              val adderUserId = Id[User](jsAdderUserId.toLong)
              new SafeFuture(shoebox.getBasicUsers(adderUserId +: addedUsers) map { basicUsers =>
                val adderUser = basicUsers(adderUserId)
                val addedBasicUsers = addedUsers.map(u => basicUsers(u))
                val addedUsersString = addedBasicUsers.map(s => s"${s.firstName} ${s.lastName}") match {
                  case first :: Nil => first
                  case first :: second :: Nil => first + " and " + second
                  case many => many.take(many.length - 1).mkString(", ") + ", and " + many.last
                }

                val friendlyMessage = s"${adderUser.firstName} ${adderUser.lastName} added $addedUsersString to the conversation."
                (friendlyMessage, Json.arr("add_participants", basicUsers(adderUserId), addedBasicUsers))
              })
            case s =>
              Promise.successful(("", Json.arr())).future
          }
          auxModifiedFuture.map { case (text, aux) =>
            m.copy(auxData = Some(aux), text = text, user = Some(BasicUser(ExternalId[User]("42424242-4242-4242-4242-000000000001"), "Kifi", "", "0.jpg")))
          }
        case None =>
          Promise.successful(m).future
      }
      modifiedMessage
    } else {
      Promise.successful(m).future
    }
  }
}
