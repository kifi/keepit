package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.UserCommander
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.ImageSize
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class MobileOrganizationInviteController @Inject() ()
/*
    userCommander: UserCommander,
    orgMemberCommander: OrganizationMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    implicit val config: PublicIdConfiguration) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def inviteUsers(pubId: PublicId[Organization]) = UserAction.async(parse.tolerantJson) { request =>
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(id) =>
        val invites = (request.body \ "invites").as[JsArray].value
        val msgOpt = (request.body \ "message").asOpt[String]
        val message = if (msgOpt == Some("")) None else msgOpt

        val validInviteList = invites.map { i =>
          val access = (i \ "access").as[OrganizationAccess]
          // Can't this throw an exception?
          val id = Left(userCommander.getByExternalId((i \ "id").as[ExternalId[User]]).id.get)
          (id, access, message)
        }
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        organizationInviteCommander.inviteUsers(id, request.userId, validInviteList).map {
          case Left(fail) => sendFailResponse(fail)
          case Right(inviteesWithAccess) =>
            val result = inviteesWithAccess.map {
              case (Left(user), access) => Json.obj("user" -> user.externalId, "access" -> access)
              case (Right(contact), access) => Json.obj("email" -> contact.email, "access" -> access)
            }
            Ok(Json.toJson(result))
        }
    }
  }

  def createAnonymousInviteToOrganization(pubId: PublicId[Organization]) = UserAction(parse.tolerantJson) { request =>
    Organization.decodePublicId(pubId) match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(orgId) =>
        val access = (request.body \ "access").as[OrganizationAccess]
        val msgOpt = (request.body \ "message").asOpt[String]
        val messageOpt = msgOpt.filter(_.nonEmpty)

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        orgInviteCommander.inviteAnonymousToLibrary(orgId, request.userId, access, messageOpt) match {
          case Left(fail) => sendFailResponse(fail)
          case Right((invite, library)) =>
            val org = orgCommander.get(orgId)
            val organizationPath = s"${fortyTwoConfig.applicationBaseUrl}${Organization.formatOrganizationPath(owner.username, organization.slug)}" // ???
            val link = organizationPath + "?authToken=" + invite.authToken
            val shortMsg = s"You've been invited to a team on Kifi: $link"
            Ok(Json.obj(
              "link" -> link,
              "access" -> invite.access.value,
              "sms" -> shortMsg,
              "email" -> Json.obj(
                "subject" -> s"You've been invited to a team on Kifi: ${org.name}",
                "body" -> s"Join us at: $link"
              ),
              "facebook" -> shortMsg,
              "twitter" -> shortMsg,
              "message" -> "" // Ignore!
            ))
        }
    }
  }

  private def sendFailResponse(fail: OrganiaztionFail) = Status(fail.status)(Json.obj("error" -> fail.message))

}
*/
