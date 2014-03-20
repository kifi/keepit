package com.keepit.controllers.website

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, WebsiteController}
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, State}
import com.keepit.model._
import com.keepit.social._
import com.keepit.common.akka.TimeoutFuture
import com.keepit.heimdal._
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._
import play.api.templates.Html
import com.keepit.abook.ABookServiceClient
import play.api.mvc.Cookie
import com.keepit.model.Invitation
import com.keepit.commanders.{InviteStatus, FullSocialId, InviteCommander}
import com.keepit.inject.FortyTwoConfig

case class BasicUserInvitation(name: String, picture: Option[String], state: State[Invitation])

class InviteController @Inject() (db: Database,
  userRepo: UserRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialGraphPlugin: SocialGraphPlugin,
  actionAuthenticator: ActionAuthenticator,
  abookServiceClient: ABookServiceClient,
  inviteCommander: InviteCommander,
  fortytwoConfig: FortyTwoConfig
) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def invite = HtmlAction.authenticated { implicit request =>
    Redirect("/friends/invite") // Can't use reverse routes because we need to send to this URL exactly
  }

  private def CloseWindow() = Ok(Html("<script>window.close()</script>"))

  def inviteConnection = HtmlAction.authenticatedAsync { implicit request =>
    val resultOption = for {
      form <- request.body.asFormUrlEncoded
      fullSocialIdString <- form.get("fullSocialId").map(_.head)
      fullSocialId <- FullSocialId.fromString(fullSocialIdString)
    } yield {
      val subject = form.get("subject").map(_.head)
      val message = form.get("message").map(_.head)
      inviteCommander.invite(request.userId, fullSocialId, subject, message).map {
        case inviteStatus if inviteStatus.sent => CloseWindow()
        case InviteStatus(false, Some(facebookInvite), "client_handle") if fullSocialId.network == SocialNetworks.FACEBOOK =>
          Redirect(inviteCommander.fbInviteUrl(facebookInvite, fullSocialId.identifier.left.get))
        case _ => InternalServerError("0")
      }
    }
    resultOption getOrElse Future.successful(CloseWindow())
  }

  def confirmInvite(id: ExternalId[Invitation], errorMsg: Option[String], errorCode: Option[Int]) = Action {
    if (inviteCommander.confirmFacebookInvite(id: ExternalId[Invitation], errorMsg: Option[String], errorCode: Option[Int]).sent) { CloseWindow() }
    else { Redirect(routes.HomeController.home) }
  }

  def refreshAllSocialInfo() = HtmlAction.authenticatedAsync { implicit request =>
    val info = db.readOnly { implicit s =>
      socialUserInfoRepo.getByUser(request.userId)
    }
    implicit val duration = 5.minutes
    TimeoutFuture(Future.sequence(info.map(socialGraphPlugin.asyncFetch(_)))).map { res =>
      Redirect("/friends/invite")
    }
  }

  def acceptInvite(id: ExternalId[Invitation]) = HtmlAction.async(allowPending = true)(authenticatedAction = { implicit request =>
    resolve(Redirect(com.keepit.controllers.core.routes.AuthController.signupPage))
  }, unauthenticatedAction = { implicit request =>
      val (invitation, inviterUserOpt) = db.readOnly { implicit session =>
        invitationRepo.getOpt(id).map {
          case invite if invite.senderUserId.isDefined =>
            (Some(invite), Some(userRepo.get(invite.senderUserId.get)))
          case invite =>
            (Some(invite), None)
        }.getOrElse((None, None))
      }
      invitation match {
        case Some(invite) if invite.state == InvitationStates.ACTIVE || invite.state == InvitationStates.INACTIVE =>
          if (request.identityOpt.isDefined || invite.senderUserId.isEmpty) {
            resolve(Redirect(com.keepit.controllers.website.routes.HomeController.home).withCookies(Cookie("inv", invite.externalId.id)))
          } else {
            val nameOpt = (invite.recipientSocialUserId, invite.recipientEContactId) match {
              case (Some(socialUserId), _) =>
                val name = db.readOnly(socialUserInfoRepo.get(socialUserId)(_).fullName)
                Promise.successful(Option(name)).future
              case (_, Some(eContactId)) =>
                abookServiceClient.getEContactById(eContactId).map { cOpt => cOpt.map(_.name.getOrElse("")) }
              case _ =>
                Promise.successful(None).future
            }
            nameOpt.map {
              case Some(name) =>
                val inviter = inviterUserOpt.get.firstName
                Ok(views.html.marketing.landing(
                    useCustomMetaData = true,
                    pageUrl = fortytwoConfig.applicationBaseUrl + request.uri,
                    titleText = s"$inviter sent you an invite to kifi",
                    titleDesc = s"$inviter uses kifi to easily keep anything online - an article, video, picture, or email - then quickly find personal and friend's keeps on top of search results."
                )).withCookies(Cookie("inv", invite.externalId.id))
              case None =>
                log.warn(s"[acceptInvite] invitation record $invite has neither recipient social id or econtact id")
                Redirect(com.keepit.controllers.website.routes.HomeController.home)
            }
          }
        case _ =>
          resolve(Redirect(com.keepit.controllers.website.routes.HomeController.home))
      }
  })
}
