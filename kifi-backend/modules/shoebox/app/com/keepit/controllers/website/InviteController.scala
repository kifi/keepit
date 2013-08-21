package com.keepit.controllers.website

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import java.net.URLEncoder

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.ExternalId
import com.keepit.common.db.State
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.net.HttpClient
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.social.{SocialNetworks, SocialNetworkType, SocialId}

import play.api.Play.current
import play.api._
import play.api.mvc._

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
  actionAuthenticator: ActionAuthenticator,
  httpClient: HttpClient)
    extends WebsiteController(actionAuthenticator) {

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

  private val url = current.configuration.getString("application.baseUrl").get
  private val appId = current.configuration.getString("securesocial.facebook.clientId").get
  private def fbInviteUrl(invite: Invitation)(implicit session: RSession): Future[String] = {
    val identity = socialUserInfoRepo.get(invite.recipientSocialUserId)
    val link = URLEncoder.encode(s"$url${routes.InviteController.acceptInvite(invite.externalId)}", "UTF-8")
    val confirmUri = URLEncoder.encode(
      s"$url${routes.InviteController.confirmInvite(invite.externalId, None, None)}", "UTF-8")
    // Workaround for https://developers.facebook.com/bugs/314349658708936
    // See https://developers.facebook.com/bugs/576522152386211
    httpClient.getFuture(s"https://developers.facebook.com/tools/debug/og/object?q=$link") map { _ =>
      s"https://www.facebook.com/dialog/send?app_id=$appId&link=$link&redirect_uri=$confirmUri&to=${identity.socialId.id}"
    }
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
            Async { fbInviteUrl(invitationRepo.save(invite)) map (Redirect(_)) }
          case SocialNetworks.LINKEDIN =>
            val me = socialUserInfoRepo.getByUser(request.userId)
                .find(_.networkType == SocialNetworks.LINKEDIN).get
            val path = routes.InviteController.acceptInvite(invite.externalId).url
            val messageWithUrl = s"${message getOrElse ""}\n$url$path"
            linkedIn.sendMessage(me, socialUserInfo, subject.getOrElse(""), messageWithUrl)
            invitationRepo.save(invite.withState(InvitationStates.ACTIVE))
            Redirect(routes.InviteController.invite)
          case _ =>
            BadRequest("Unsupported social network")
        }
      }

      if(fullSocialId.size != 2) {
        Redirect(routes.InviteController.invite)
      } else {
        val socialUserInfo = socialUserInfoRepo.get(SocialId(fullSocialId(1)), SocialNetworkType(fullSocialId(0)))
        invitationRepo.getByRecipient(socialUserInfo.id.get) match {
          case Some(alreadyInvited) if alreadyInvited.state != InvitationStates.INACTIVE =>
            if(alreadyInvited.senderUserId == request.user.id) {
              sendInvitation(socialUserInfo, alreadyInvited)
            } else {
              Redirect(routes.InviteController.invite)
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
              Redirect(routes.InviteController.invite)
            }
        }
      }
    }
  }

  def acceptInvite(id: ExternalId[Invitation]) = Action {
    db.readOnly { implicit session =>
      val invitation = invitationRepo.getOpt(id)
      invitation match {
        case Some(invite) if (invite.state == InvitationStates.ACTIVE || invite.state == InvitationStates.INACTIVE) =>
          val socialUser = socialUserInfoRepo.get(invitation.get.recipientSocialUserId)
          Ok(views.html.website.welcome(Some(id), Some(socialUser)))
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
          }
          Redirect(routes.InviteController.invite)
        case None => Redirect(routes.HomeController.home)
      }
    }
  }

  def userCanInvite(experiments: Set[State[ExperimentType]]) = {
    Play.isDev || (experiments & Set(ExperimentTypes.ADMIN, ExperimentTypes.CAN_INVITE) nonEmpty)
  }
}
