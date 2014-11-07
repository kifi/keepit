package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.{ FailedInvitationException, FullSocialId, InviteCommander, InviteStatus }
import com.keepit.common.akka.TimeoutFuture
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ Invitation, _ }
import com.keepit.social._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc.{ Cookie, _ }
import play.twirl.api.Html

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class InviteController @Inject() (db: Database,
    userRepo: UserRepo,
    socialUserRepo: SocialUserInfoRepo,
    emailRepo: UserEmailAddressRepo,
    userConnectionRepo: UserConnectionRepo,
    invitationRepo: InvitationRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    socialGraphPlugin: SocialGraphPlugin,
    val userActionsHelper: UserActionsHelper,
    abookServiceClient: ABookServiceClient,
    inviteCommander: InviteCommander,
    fortytwoConfig: FortyTwoConfig,
    airbrake: AirbrakeNotifier) extends UserActions with ShoeboxServiceController {

  def invite = UserAction { implicit request =>
    Redirect("/friends/invite") // Can't use reverse routes because we need to send to this URL exactly
  }

  private def CloseWindow() = Ok(Html("<script>window.close()</script>"))

  def inviteConnection = UserAction.async { implicit request =>
    val form = request.body.asFormUrlEncoded.get
    Try(FullSocialId(form.get("fullSocialId").get.head)) match {
      case Failure(_) => Future.successful(BadRequest("0"))
      case Success(fullSocialId) =>
        val subject = form.get("subject").map(_.head)
        val message = form.get("message").map(_.head)
        val source = "site"
        inviteCommander.invite(request, request.userId, fullSocialId, subject, message, source).map {
          case inviteStatus if inviteStatus.sent =>
            log.info(s"[inviteConnection] Invite sent: $inviteStatus")
            CloseWindow()
          case InviteStatus(false, Some(facebookInvite), "client_handle") if fullSocialId.network == SocialNetworks.FACEBOOK =>
            val facebookUrl = inviteCommander.fbInviteUrl(facebookInvite.externalId, fullSocialId.identifier.left.get, source)
            log.info(s"[inviteConnection] Redirecting user ${request.userId} to Facebook: $facebookUrl")
            Redirect(facebookUrl)
          case failedInviteStatus => {
            log.error(s"[inviteConnection] Unexpected error while processing invitation from ${request.userId} to ${fullSocialId}: $failedInviteStatus")
            airbrake.notify(new FailedInvitationException(failedInviteStatus, None, Some(request.userId), Some(fullSocialId)))
            InternalServerError("0")
          }
        }
    }
  }

  //will replace 'invite' and be renamed once the new site is fully released.
  //At which point we can also remove the two step facebook invitation process
  def inviteV2() = UserAction.async(parse.tolerantJson) { request =>
    val fullSocialIdOption = (request.body \ "id").asOpt[FullSocialId]
    fullSocialIdOption match {
      case None => Future.successful(BadRequest("0"))
      case Some(fullSocialId) => {
        inviteCommander.invite(request, request.userId, fullSocialId, None, None, "site").map {
          case inviteStatus if inviteStatus.sent => {
            log.info(s"[invite] Invite sent: $inviteStatus")
            Ok(Json.obj("sent" -> true))
          }
          case InviteStatus(false, Some(facebookInvite), "client_handle") if fullSocialId.network == SocialNetworks.FACEBOOK =>
            val json = Json.obj(
              "url" -> inviteCommander.acceptUrl(facebookInvite.externalId)
            )
            inviteCommander.confirmFacebookInvite(Some(request), facebookInvite.externalId, "site", None, None)
            log.info(s"[inviteV2] ${request.userId} to Facebook")
            Ok(json)
          case failedInviteStatus => {
            log.error(s"[invite] Unexpected error while processing invitation from ${request.userId} to ${fullSocialId}: $failedInviteStatus")
            airbrake.notify(new FailedInvitationException(failedInviteStatus, None, Some(request.userId), Some(fullSocialId)))
            Ok(Json.obj(
              "error" -> failedInviteStatus.code
            ))
          }
        }
      }
    }
  }

  def confirmInvite(id: ExternalId[Invitation], source: String, errorMsg: Option[String], errorCode: Option[Int]) = Action { request =>
    val inviteStatus = inviteCommander.confirmFacebookInvite(None, id, source, errorMsg, errorCode)
    if (inviteStatus.sent) {
      log.info(s"[confirmInvite] Facebook invite sent: $inviteStatus")
      CloseWindow()
    } else {
      log.error(s"[confirmInvite] Unexpected error while processing Facebook invitation $id from $source: $inviteStatus $errorMsg }")
      airbrake.notify(new FailedInvitationException(inviteStatus, Some(id), None, None))
      Redirect(com.keepit.controllers.website.routes.HomeController.home)
    }
  }

  def refreshAllSocialInfo() = UserAction.async { implicit request =>
    val info = db.readOnlyMaster { implicit s =>
      socialUserInfoRepo.getByUser(request.userId)
    }
    implicit val duration = 5.minutes
    TimeoutFuture(Future.sequence(info.map(socialGraphPlugin.asyncFetch(_)))).map { res =>
      Redirect("/friends/invite")
    }
  }

  def acceptInvite(id: ExternalId[Invitation]) = MaybeUserAction.async { implicit request =>
    request.userIdOpt match {
      case Some(userId) =>
        log.info(s"acceptInvite of an already user $userId for invitation $id")
        resolve(Redirect(com.keepit.controllers.core.routes.AuthController.signupPage))
      case None =>
        log.info(s"acceptInvite of a non user with invitation $id")
        val (invitation, inviterUserOpt) = db.readOnlyMaster { implicit session =>
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
              log.warn(s"request identity is ${request.identityOpt} and sender is ${invite.senderUserId}, redirecting to home")
              resolve(Redirect(com.keepit.controllers.website.routes.HomeController.home).withCookies(Cookie("inv", invite.externalId.id)))
            } else {
              val senderUserId = invite.senderUserId.get
              val nameOpt = (invite.recipientSocialUserId, invite.recipientEmailAddress) match {
                case (Some(socialUserId), _) =>
                  val name = db.readOnlyReplica(socialUserInfoRepo.get(socialUserId)(_).fullName)
                  Future.successful(Some(name))
                case (_, Some(emailAddress)) =>
                  abookServiceClient.getContactNameByEmail(senderUserId, emailAddress).map(_ orElse Some(""))
                case _ =>
                  log.warn(s"[acceptInvite] invitation record $invite has neither recipient social id or econtact id")
                  Future.successful(None)
              }
              val openGraphTags = Map(
                KifiSiteRouter.substituteMetaProperty("og:title", inviteCommander.fbTitle(inviterUserOpt.map(_.firstName))),
                KifiSiteRouter.substituteMetaProperty("og:description", inviteCommander.fbDescription),
                KifiSiteRouter.substituteMetaProperty("og:url", inviteCommander.acceptUrl(id)),
                KifiSiteRouter.substituteLink("canonical", inviteCommander.acceptUrl(id))
              )
              resolve(MarketingSiteRouter.marketingSite(substitutions = openGraphTags).withCookies(Cookie("inv", invite.externalId.id)))
            }
          case _ =>
            resolve(Redirect(com.keepit.controllers.website.routes.HomeController.home))
        }
    }
  }

}
