package com.keepit.controllers.website

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

import java.net.URLEncoder

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, AuthenticatedRequest, ActionAuthenticator, WebsiteController}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{Id, ExternalId, State}
import com.keepit.common.net.HttpClient
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.social._
import com.keepit.common.akka.{TimeoutFuture, SafeFuture}
import com.keepit.heimdal._
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.Play.current
import play.api._
import play.api.mvc._
import play.api.templates.Html
import com.keepit.common.mail._
import com.keepit.abook.ABookServiceClient
import play.api.mvc.Cookie
import com.keepit.model.Invitation
import scala.util.{Failure, Try, Success}
import com.keepit.commanders.{FullSocialId, InviteInfo, InviteCommander}
import com.keepit.inject.FortyTwoConfig

case class BasicUserInvitation(name: String, picture: Option[String], state: State[Invitation])

class InviteController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialGraphPlugin: SocialGraphPlugin,
  actionAuthenticator: ActionAuthenticator,
  httpClient: HttpClient,
  eventContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  abookServiceClient: ABookServiceClient,
  postOffice: LocalPostOffice,
  inviteCommander: InviteCommander,
  fortytwoConfig: FortyTwoConfig,
  secureSocialClientIds: SecureSocialClientIds
) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def invite = HtmlAction.authenticated { implicit request =>
    Redirect("/friends/invite") // Can't use reverse routes because we need to send to this URL exactly
  }

  private def CloseWindow() = Ok(Html("<script>window.close()</script>"))

  private val url = fortytwoConfig.applicationBaseUrl
  private val appId = secureSocialClientIds.facebook
  private def fbInviteUrl(invite: Invitation): String = {
    db.readOnly(attempts = 2) { implicit ro =>
      val identity = socialUserInfoRepo.get(invite.recipientSocialUserId.get)
      val link = URLEncoder.encode(s"$url${routes.InviteController.acceptInvite(invite.externalId)}", "UTF-8")
      val confirmUri = URLEncoder.encode(
        s"$url${routes.InviteController.confirmInvite(invite.externalId, None, None)}", "UTF-8")
      s"https://www.facebook.com/dialog/send?app_id=$appId&link=$link&redirect_uri=$confirmUri&to=${identity.socialId}"
    }
  }

  def inviteConnection = HtmlAction.authenticated { implicit request =>
    val (fullSocialId, subject, message) = request.request.body.asFormUrlEncoded match {
      case Some(form) =>
        (form.get("fullSocialId").map(_.head).getOrElse(""),
          form.get("subject").map(_.head),
          form.get("message").map(_.head))
      case None => ("", None, None)
    }

    if (fullSocialId.split("/").length != 2) CloseWindow() else {
      val inviteInfo = InviteInfo(FullSocialId(fullSocialId), subject, message)
      processInvite(request.userId, request.user, inviteInfo)
    }
  }

  def processInvite(userId:Id[User], user:User, inviteInfo:InviteInfo): SimpleResult = {
    if (inviteInfo.fullSocialId.network == "email") {
      abookServiceClient.getOrCreateEContact(userId, inviteInfo.fullSocialId.id) map { econtactTr =>
        econtactTr match {
          case Success(c) =>
            inviteCommander.sendInvitationForContact(userId, c, user, url, inviteInfo)
            log.info(s"[inviteConnection-email(${inviteInfo.fullSocialId.id}, $userId)] invite sent successfully")
          case Failure(e) =>
            log.warn(s"[inviteConnection-email(${inviteInfo.fullSocialId.id}, $userId)] cannot locate or create econtact entry; Error: $e; Cause: ${e.getCause}")
        }
      }
      CloseWindow()
    } else {
      val inviteStatus = inviteCommander.processSocialInvite(userId, inviteInfo, url)
      if (!inviteStatus.sent && inviteInfo.fullSocialId.network.equalsIgnoreCase("facebook") && inviteStatus.code == "client_handle") {
        inviteStatus.savedInvite match {
          case Some(saved) => Redirect(fbInviteUrl(saved))
          case None => { // shouldn't happen
            log.error(s"[processInvite($userId,$user,$inviteInfo)] Could not send Facebook invite")
            Status(INTERNAL_SERVER_ERROR)("0")
          }
        }
      } else CloseWindow()
    }
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
                val baseUrl = fortytwoConfig.applicationBaseUrl
                val inviter = inviterUserOpt.get.firstName
                val pageUrl = baseUrl + request.uri
                val titleText = s"$inviter sent you an invite to kifi"
                val titleDesc = s"$inviter uses kifi to easily keep anything online - an article, video, picture, or email - then quickly find personal and friend's keeps on top of search results."

                Ok(views.html.marketing.landing(
                    useCustomMetaData = true, pageUrl = pageUrl, titleText = titleText, titleDesc = titleDesc
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

  def confirmInvite(id: ExternalId[Invitation], errorMsg: Option[String], errorCode: Option[Int]) = Action {
    db.readWrite { implicit session =>
      val invitation = invitationRepo.getOpt(id)
      invitation match {
        case Some(invite) =>
          if (errorCode.isEmpty) {
            invitationRepo.save(invite.copy(state = InvitationStates.ACTIVE))
            inviteCommander.reportSentInvitation(invite, SocialNetworks.FACEBOOK)
          }
          CloseWindow()
        case None =>
          Redirect(routes.HomeController.home)
      }
    }
  }

}
