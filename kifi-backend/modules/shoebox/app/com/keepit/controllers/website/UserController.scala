package com.keepit.controllers.website

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.slick._
import com.keepit.common.social.{SocialNetworks, BasicUserRepo}
import com.keepit.common.time._
import com.keepit.model._

import play.api.libs.json._

@Singleton
class UserController @Inject() (db: Database,
  basicUserRepo: BasicUserRepo,
  userConnectionRepo: UserConnectionRepo,
  emailRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserRepo: SocialUserInfoRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def connections() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "connections" -> db.readOnly { implicit s =>
        userConnectionRepo.getConnectedUsers(request.userId).map(basicUserRepo.load).toSeq
      }
    ))
  }

  def currentUser() = AuthenticatedJsonAction { request =>
    Ok(Json.toJson(db.readOnly { implicit s => basicUserRepo.load(request.userId) }))
  }

  def updateEmail() = AuthenticatedJsonToJsonAction(true) { request =>
    val o = request.request.body
    val email = (o \ "email").as[String]
    db.readWrite{ implicit session =>
      if(emailRepo.getByAddressOpt(email).isEmpty) {
        emailRepo.getByUser(request.user.id.get) map { oldEmail =>
          emailRepo.save(oldEmail.withState(EmailAddressStates.INACTIVE))
        }
        emailRepo.save(EmailAddress(address = email, userId = request.user.id.get))
      }
    }
    Ok
  }

  def getAllConnections = AuthenticatedJsonAction { request =>

    val connections = db.readOnly { implicit conn =>
      socialUserRepo.getByUser(request.user.id.get) flatMap { su =>
        socialConnectionRepo.getSocialUserConnections(su.id.get) map { suc =>

          val status = if(suc.userId.isDefined) "joined"
            else {
              val existingInvite = invitationRepo.getByRecipient(suc.id.get)
              if(existingInvite.isDefined && existingInvite.get.state != InvitationStates.INACTIVE) "invited"
              else ""
            }
          (suc, status)
        }
      }
    }

    Ok(JsArray(connections.map { conn =>
      Json.obj(
        "label" -> conn._1.fullName,
        "image" -> Json.toJson(conn._1.networkType match {
          case SocialNetworks.FACEBOOK =>
            Some(s"https://graph.facebook.com/${conn._1.socialId.id}/picture?type=square&width=75&height=75")
          case _ => conn._1.pictureUrl
        }),
        "value" -> (conn._1.networkType + "/" + conn._1.socialId.id),
        "status" -> conn._2
      )
    }))

  }
}
