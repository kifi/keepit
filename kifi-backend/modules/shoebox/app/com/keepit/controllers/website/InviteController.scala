package com.keepit.controllers.website

import scala.concurrent.Await
import scala.concurrent.duration._

import java.net.URLEncoder

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, State}
import com.keepit.common.net.HttpClient
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.social.{SocialGraphPlugin, SocialNetworks, SocialNetworkType, SocialId}
import com.keepit.common.akka.SafeFuture
import com.keepit.heimdal.{HeimdalServiceClient, UserEventContextBuilderFactory, UserEvent, UserEventType}

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.Play.current
import play.api._
import play.api.mvc._
import play.api.templates.Html

case class BasicUserInvitation(name: String, picture: Option[String], state: State[Invitation])

class InviteController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  linkedIn: LinkedInSocialGraph,
  socialGraphPlugin: SocialGraphPlugin,
  actionAuthenticator: ActionAuthenticator,
  httpClient: HttpClient,
  userEventContextBuilder: UserEventContextBuilderFactory,
  heimdal: HeimdalServiceClient)
    extends WebsiteController(actionAuthenticator) {

  private def newSignup()(implicit request: Request[_]) =
    request.cookies.get("QA").isDefined || current.configuration.getBoolean("newSignup").getOrElse(false)

  private def createBasicUserInvitation(socialUser: SocialUserInfo, state: State[Invitation]): BasicUserInvitation = {
    BasicUserInvitation(name = socialUser.fullName, picture = socialUser.getPictureUrl(75, 75), state = state)
  }

  def invite = AuthenticatedHtmlAction { implicit request =>
    if(userCanInvite(request.experiments)) {
      val friendsOnKifi = db.readOnly { implicit session =>
        userConnectionRepo.getConnectedUsers(request.user.id.get) flatMap { u =>
          val user = userRepo.get(u)
          if(user.state == UserStates.ACTIVE) Some(user.externalId)
          else None
        }
      }

      val (invites, invitesLeft, invitesSent, invitesAccepted) = db.readOnly { implicit session =>
        val totalAllowedInvites = userValueRepo.getValue(request.user.id.get, "availableInvites").map(_.toInt).getOrElse(6)
        val currentInvitations = invitationRepo.getByUser(request.user.id.get).map{ s =>
          val socialUser = socialUserRepo.get(s.recipientSocialUserId)
          Some(createBasicUserInvitation(socialUser, s.state))
        }
        val left = totalAllowedInvites - currentInvitations.length
        val sent = currentInvitations.length
        val accepted = currentInvitations.count( s => if(s.isDefined && s.get.state == InvitationStates.JOINED) true else false)
        val invites = currentInvitations ++ Seq.fill(left)(None)

        (invites, left, sent, accepted)
      }

      Ok(views.html.website.inviteFriends(request.user, friendsOnKifi, invites, invitesLeft, invitesSent, invitesAccepted))
    }
    else {
      Redirect(routes.HomeController.home())
    }
  }

  private def CloseWindow() = Ok(Html("<script>window.close()</script>"))

  private val url = current.configuration.getString("application.baseUrl").get
  private val appId = current.configuration.getString("securesocial.facebook.clientId").get
  private def fbInviteUrl(invite: Invitation)(implicit session: RSession): String = {
    val identity = socialUserInfoRepo.get(invite.recipientSocialUserId)
    val link = URLEncoder.encode(s"$url${routes.InviteController.acceptInvite(invite.externalId)}", "UTF-8")
    val confirmUri = URLEncoder.encode(
      s"$url${routes.InviteController.confirmInvite(invite.externalId, None, None)}", "UTF-8")
    s"https://www.facebook.com/dialog/send?app_id=$appId&link=$link&redirect_uri=$confirmUri&to=${identity.socialId}"
  }

  def inviteConnection = AuthenticatedHtmlAction { implicit request =>
    val (fullSocialId, subject, message) = request.request.body.asFormUrlEncoded match {
      case Some(form) =>
        (form.get("fullSocialId").map(_.head).getOrElse("").split("/"),
          form.get("subject").map(_.head), form.get("message").map(_.head))
      case None => (Array(), None, None)
    }
    db.readWrite { implicit session =>

      def sendInvitation(socialUserInfo: SocialUserInfo, invite: Invitation) = {
        socialUserInfo.networkType match {
          case SocialNetworks.FACEBOOK =>
            Redirect(fbInviteUrl(invitationRepo.save(invite)))
          case SocialNetworks.LINKEDIN =>
            val me = socialUserInfoRepo.getByUser(request.userId)
                .find(_.networkType == SocialNetworks.LINKEDIN).get
            val path = routes.InviteController.acceptInvite(invite.externalId).url
            val messageWithUrl = s"${message getOrElse ""}\n$url$path"
            linkedIn.sendMessage(me, socialUserInfo, subject.getOrElse(""), messageWithUrl)
            invitationRepo.save(invite.withState(InvitationStates.ACTIVE))
            CloseWindow()
          case _ =>
            BadRequest("Unsupported social network")
        }
      }

      if(fullSocialId.size != 2) {
        CloseWindow()
      } else {
        val socialUserInfo = socialUserInfoRepo.get(SocialId(fullSocialId(1)), SocialNetworkType(fullSocialId(0)))
        invitationRepo.getByRecipient(socialUserInfo.id.get) match {
          case Some(alreadyInvited) if alreadyInvited.state != InvitationStates.INACTIVE =>
            if(alreadyInvited.senderUserId == request.user.id) {
              sendInvitation(socialUserInfo, alreadyInvited)
            } else {
              CloseWindow()
            }
          case inactiveOpt =>
            val totalAllowedInvites = userValueRepo.getValue(request.user.id.get, "availableInvites").map(_.toInt).getOrElse(6)
            val currentInvitations = invitationRepo.getByUser(request.user.id.get).collect {
              case s if s.state != InvitationStates.INACTIVE =>
                Some(createBasicUserInvitation(socialUserRepo.get(s.recipientSocialUserId), s.state))
            }
            if (currentInvitations.length < totalAllowedInvites) {
              val invite = inactiveOpt map {
                _.copy(senderUserId = Some(request.user.id.get))
              } getOrElse Invitation(
                senderUserId = Some(request.user.id.get),
                recipientSocialUserId = socialUserInfo.id.get,
                state = InvitationStates.INACTIVE
              )
              sendInvitation(socialUserInfo, invite)
            } else {
              CloseWindow()
            }
        }
      }
    }
  }

  def refreshAllSocialInfo() = AuthenticatedHtmlAction { implicit request =>
    for (info <- db.readOnly { implicit s =>
      socialUserInfoRepo.getByUser(request.userId)
    }) {
      Await.result(socialGraphPlugin.asyncFetch(info), 5 minutes)
    }
    Redirect("/friends/invite")
  }

  def acceptInvite(id: ExternalId[Invitation]) = Action { implicit request =>
    db.readOnly { implicit session =>
      val invitation = invitationRepo.getOpt(id)
      invitation match {
        case Some(invite) if (invite.state == InvitationStates.ACTIVE || invite.state == InvitationStates.INACTIVE) =>
          val socialUser = socialUserInfoRepo.get(invitation.get.recipientSocialUserId)
          Ok(views.html.website.welcome(Some(id), Some(socialUser), newSignup = newSignup))
        case _ =>
          Redirect(routes.HomeController.home)
      }
    }
  }

  def confirmInvite(id: ExternalId[Invitation], errorMsg: Option[String], errorCode: Option[Int]) = Action {
    db.readWrite { implicit session =>
      val invitation = invitationRepo.getOpt(id)
      invitation match {
        case Some(invite) =>
          if (errorCode.isEmpty) {
            invitationRepo.save(invite.copy(state = InvitationStates.ACTIVE))
            SafeFuture{
              val contextBuilder = userEventContextBuilder()
              contextBuilder += ("invitee", invite.recipientSocialUserId.id)
              heimdal.trackEvent(UserEvent(invite.senderUserId.map(_.id).getOrElse(-1), contextBuilder.build, UserEventType("invite_sent")))
            }
          }
          CloseWindow()
        case None =>
          Redirect(routes.HomeController.home)
      }
    }
  }

  def userCanInvite(experiments: Set[ExperimentType]) = {
    Play.isDev || (experiments & Set(ExperimentType.ADMIN, ExperimentType.CAN_INVITE) nonEmpty)
  }
}
