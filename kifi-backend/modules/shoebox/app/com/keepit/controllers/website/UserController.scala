package com.keepit.controllers.website

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AuthenticatedRequest, ActionAuthenticator, WebsiteController}
import com.keepit.common.db.slick._
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.realtime.{DeviceType, UrbanAirship}

import play.api.libs.json._

@Singleton
class UserController @Inject() (db: Database,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  userConnectionRepo: UserConnectionRepo,
  emailRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserRepo: SocialUserInfoRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator,
  urbanAirship: UrbanAirship)
    extends WebsiteController(actionAuthenticator) {

  def registerDevice(deviceType: String) = AuthenticatedJsonToJsonAction { implicit request =>
    (request.body \ "token").asOpt[String] map { token =>
      val device = urbanAirship.registerDevice(request.userId, token, DeviceType(deviceType))
      Ok(Json.obj(
        "token" -> device.token
      ))
    } getOrElse {
      BadRequest(Json.obj(
        "error" -> "Body must contain a token parameter"
      ))
    }
  }

  def connections() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "connections" -> db.readOnly { implicit s =>
        userConnectionRepo.getConnectedUsers(request.userId).map(basicUserRepo.load).toSeq
      }
    ))
  }

  private case class BasicSocialUser(network: String, profileUrl: Option[String], pictureUrl: Option[String])
  private object BasicSocialUser {
    implicit val writesBasicSocialUser = Json.writes[BasicSocialUser]
    def from(sui: SocialUserInfo): BasicSocialUser =
      BasicSocialUser(network = sui.networkType.name, profileUrl = sui.getProfileUrl, pictureUrl = sui.getPictureUrl())
  }

  def socialNetworkInfo() = AuthenticatedJsonAction { request =>
    Ok(Json.toJson(db.readOnly { implicit s =>
      socialUserRepo.getByUser(request.userId).map(BasicSocialUser from _)
    }))
  }

  def currentUser = AuthenticatedJsonAction(true) { implicit request => getUserInfo(request) }

  private case class UpdatableUserInfo(
      description: Option[String], emails: Option[Seq[String]],
      firstName: Option[String] = None, lastName: Option[String] = None)
  private implicit val updatableUserDataFormat = Json.format[UpdatableUserInfo]

  def updateCurrentUser() = AuthenticatedJsonToJsonAction(true) { implicit request =>
    request.body.asOpt[UpdatableUserInfo] map { userData =>
      db.readWrite { implicit session =>
        userData.description foreach { userValueRepo.setValue(request.userId, "user_description", _) }
        if (userData.firstName.isDefined || userData.lastName.isDefined) {
          val user = userRepo.get(request.userId)
          userRepo.save(user.copy(
            firstName = userData.firstName getOrElse user.firstName,
            lastName = userData.lastName getOrElse user.lastName
          ))
        }
        for (emails <- userData.emails) {
          val (existing, toRemove) = emailRepo.getByUser(request.user.id.get).partition(emails contains _.address)
          for (email <- toRemove) {
            emailRepo.save(email.withState(EmailAddressStates.INACTIVE))
          }
          for (address <- emails.toSet -- existing.map(_.address)) {
            emailRepo.save(EmailAddress(userId = request.userId, address = address))
          }
        }
      }
      getUserInfo(request)
    } getOrElse {
      BadRequest(Json.obj("error" -> "could not parse user info from body"))
    }
  }

  private def getUserInfo[T](request: AuthenticatedRequest[T]) = {
    val basicUser = db.readOnly { implicit s => basicUserRepo.load(request.userId) }
    val info = db.readOnly { implicit s =>
      UpdatableUserInfo(
        description = Some(userValueRepo.getValue(request.userId, "user_description").getOrElse("")),
        emails = Some(emailRepo.getByUser(request.userId).map(_.address))
      )
    }
    Ok(Json.toJson(basicUser).as[JsObject] deepMerge Json.toJson(info).as[JsObject])
  }

  private val SitePrefNames = Set("site_left_col_width")

  def getPrefs() = AuthenticatedJsonAction { request =>
    Ok(db.readOnly { implicit s =>
      JsObject(SitePrefNames.toSeq.map { name =>
        name -> userValueRepo.getValue(request.userId, name).map(JsString).getOrElse(JsNull)
      })
    })
  }

  def savePrefs() = AuthenticatedJsonToJsonAction { request =>
    val o = request.request.body.as[JsObject]
    if (o.keys.subsetOf(SitePrefNames)) {
      db.readWrite { implicit s =>
        o.fields.foreach { case (name, value) =>
          userValueRepo.setValue(request.userId, name, value.as[String])  // TODO: deactivate pref if JsNull
        }
      }
      Ok(o)
    } else {
      BadRequest(Json.obj("error" -> ((SitePrefNames -- o.keys).mkString(", ") + " not recognized")))
    }
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
        "image" -> Json.toJson(conn._1.getPictureUrl(75, 75)),
        "value" -> (conn._1.networkType + "/" + conn._1.socialId.id),
        "status" -> conn._2
      )
    }))

  }
}
