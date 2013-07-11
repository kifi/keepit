package com.keepit.controllers.website

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.ExternalId
import com.keepit.common.db.State
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.social._
import com.keepit.model._

import play.api.Play.current
import play.api._
import play.api.mvc._
import com.keepit.social.{SocialNetworks, SocialNetworkType, SocialId}

case class BasicUserInvitation(name: String, picture: Option[String], state: State[Invitation])

@Singleton
class InviteController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  linkedIn: LinkedInSocialGraph,
  actionAuthenticator: ActionAuthenticator)
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
  private def fbInviteUrl(invite: Invitation)(implicit session: RSession) = {
    val identity = socialUserInfoRepo.get(invite.recipientSocialUserId)
    s"https://www.facebook.com/dialog/send?app_id=$appId&name=You're%20invited%20to%20try%20KiFi!&picture=https://www.kifi.com/assets/images/kifi-fb-square.png&link=$url/invite/${invite.externalId.id}&description=Hey%20${identity.fullName}!%20You're%20invited%20to%20join%20KiFi.%20Click%20here%20to%20sign%20up&redirect_uri=$url/invite/confirm/${invite.externalId}&to=${identity.socialId.id}"
  }

  def inviteConnection = AuthenticatedHtmlAction { implicit request =>
    val (fullSocialId, subject, message) = request.request.body.asFormUrlEncoded match {
      case Some(form) =>
        (form.get("fullSocialId").map(_.head).getOrElse("").split("/"),
          form.get("subject").map(_.head), form.get("message").map(_.head))
      case None => (Array(), None, None)
    }
    db.readWrite { implicit session =>
      if(fullSocialId.size != 2) {
        Redirect(routes.InviteController.invite)
      } else {
        val socialUserInfo = socialUserInfoRepo.get(SocialId(fullSocialId(1)), SocialNetworkType(fullSocialId(0)))
        invitationRepo.getByRecipient(socialUserInfo.id.get) match {
          case Some(alreadyInvited) =>
            if(alreadyInvited.senderUserId == request.user.id.get) {
              Redirect(fbInviteUrl(alreadyInvited))
            } else {
              Redirect(routes.InviteController.invite)
            }
          case None =>
            val totalAllowedInvites = userValueRepo.getValue(request.user.id.get, "availableInvites").map(_.toInt).getOrElse(6)
            val currentInvitations = invitationRepo.getByUser(request.user.id.get).map{ s =>
              Some(createBasicUserInvitation(socialUserRepo.get(s.recipientSocialUserId), s.state))
            }
            val left = totalAllowedInvites - currentInvitations.length
            if(left > 0) {
              val invite = Invitation(
                senderUserId = Some(request.user.id.get),
                recipientSocialUserId = socialUserInfo.id.get,
                state = InvitationStates.INACTIVE
              )
              socialUserInfo.networkType match {
                case SocialNetworks.FACEBOOK =>
                  Redirect(fbInviteUrl(invitationRepo.save(invite)))
                case SocialNetworks.LINKEDIN =>
                  val me = socialUserInfoRepo.getByUser(request.userId)
                    .find(_.networkType == SocialNetworks.LINKEDIN).get
                  val path = com.keepit.controllers.website.routes.InviteController.acceptInvite(
                    invite.externalId).url
                  val messageWithUrl = s"${message getOrElse ""}\n$url$path"
                  linkedIn.sendMessage(me, socialUserInfo, subject.getOrElse(""), messageWithUrl)
                  invitationRepo.save(invite.withState(InvitationStates.ACTIVE))
                  Redirect(routes.InviteController.invite)
                case _ =>
                  BadRequest("Unsupported social network")
              }
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
        case Some(invite) if invite.state == InvitationStates.ACTIVE =>
          val socialUser = socialUserInfoRepo.get(invitation.get.recipientSocialUserId)
          Ok(views.html.website.welcome(Some(id), Some(socialUser)))
        case _ =>
          Redirect(routes.HomeController.home)
      }
    }
  }

  def confirmInvite(id: ExternalId[Invitation]) = Action {
    db.readWrite { implicit session =>
      val invitation = invitationRepo.getOpt(id)
      invitation match {
        case Some(invite) =>
          invitationRepo.save(invite.copy(state = InvitationStates.ACTIVE))
          Redirect(routes.InviteController.invite)
        case None => Redirect(routes.HomeController.home)
      }
    }
  }

  def userCanInvite(experiments: Set[State[ExperimentType]]) = {
    Play.isDev || (experiments & Set(ExperimentTypes.ADMIN, ExperimentTypes.CAN_INVITE) nonEmpty)
  }
}
