package com.keepit.controllers.mobile

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.EmailAddress
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._
import play.api.mvc.{ Action, AnyContent }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[MobileOrganizationInviteControllerImpl])
trait MobileOrganizationInviteController {
  def inviteUsers(pubId: PublicId[Organization]): Action[JsValue]
  def createAnonymousInviteToOrganization(pubId: PublicId[Organization]): Action[JsValue]
  def acceptInvitation(pubId: PublicId[Organization]): Action[AnyContent]
  def declineInvitation(pubId: PublicId[Organization]): Action[AnyContent]
}

@Singleton
class MobileOrganizationInviteControllerImpl @Inject() (
    userCommander: UserCommander,
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    orgInviteCommander: OrganizationInviteCommander,
    fortyTwoConfig: FortyTwoConfig,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends MobileOrganizationInviteController with UserActions with OrganizationAccessActions with ShoeboxServiceController {

  private def sendFailResponse(fail: OrganizationFail) = Status(fail.status)(Json.obj("error" -> fail.message))

  //inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[(Either[Id[User], EmailAddress], OrganizationRole, Option[String])])
  // : Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]]
  def inviteUsers(pubId: PublicId[Organization]) = UserAction.async(parse.tolerantJson) { request =>
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(orgId) =>
        val invites = (request.body \ "invites").as[JsArray].value
        val msg = (request.body \ "message").asOpt[String].filter(_.nonEmpty)

        // TODO: can we use PublicId[User] here instead of ExternalId[User]?
        val userInvites = invites.filter(inv => (inv \ "type").as[String] == "user")
        val emailInvites = invites.filter(inv => (inv \ "type").as[String] == "email")

        val userExternalIds = userInvites.map(inv => (inv \ "id").as[ExternalId[User]])
        val userMap = userCommander.getByExternalIds(userExternalIds)
        val userInfo = for ((extId, inv) <- userExternalIds zip userInvites) yield {
          val userId = userMap(extId).id.get
          OrganizationMemberInvitation(Left(userId), (inv \ "role").as[OrganizationRole], msg)
        }

        val emails = emailInvites.map(inv => (inv \ "id").as[EmailAddress])
        val emailInfo = for ((email, inv) <- emails zip emailInvites) yield {
          OrganizationMemberInvitation(Right(email), (inv \ "role").as[OrganizationRole], msg)
        }

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        val inviteResult = orgInviteCommander.inviteUsersToOrganization(orgId, request.userId, userInfo ++ emailInfo)
        inviteResult.map {
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
        BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val role = (request.body \ "role").as[OrganizationRole]
        val msg = (request.body \ "message").asOpt[String].filter(_.nonEmpty)

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        orgInviteCommander.universalInviteLink(orgId, request.userId, role, msg) match {
          case Left(fail) => sendFailResponse(fail)
          case Right((invite, org)) =>
            // TODO: should we be using the organization handle here?
            val organizationPath = s"${fortyTwoConfig.applicationBaseUrl}/${org.handle.get.original}"
            val link = organizationPath + "?authToken=" + invite.authToken
            val shortMsg = s"You've been invited to an organization on Kifi: $link"
            Ok(Json.obj(
              "link" -> link,
              "role" -> invite.role,
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

  def acceptInvitation(pubId: PublicId[Organization]) = UserAction { request =>
    Organization.decodePublicId(pubId) match {
      case Success(orgId) =>
        orgInviteCommander.acceptInvitation(orgId, request.userId) match {
          case Right(organizationMembership) => Ok(JsString("success"))
          case Left(organizationFail) => organizationFail.asResponse
        }
      case Failure(_) => OrganizationFail.INVALID_PUBLIC_ID.asResponse
    }
  }

  def declineInvitation(pubId: PublicId[Organization]) = UserAction { request =>
    Organization.decodePublicId(pubId) match {
      case Success(orgId) =>
        organizationInviteCommander.declineInvitation(orgId, request.userId)
        Ok(JsString("success"))
      case _ => OrganizationFail.INVALID_PUBLIC_ID.asResponse
    }
  }
}
