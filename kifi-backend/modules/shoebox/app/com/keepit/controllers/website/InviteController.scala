package com.keepit.controllers.website

import scala.concurrent.{Promise, Await}
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
import com.keepit.heimdal.{HeimdalServiceClient, EventContextBuilderFactory, UserEvent, EventType}
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.Play.current
import play.api._
import play.api.mvc._
import play.api.templates.Html
import com.keepit.common.mail._
import com.keepit.abook.ABookServiceClient
import play.api.mvc.Cookie
import com.keepit.social.SocialId
import com.keepit.model.Invitation

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
  eventContextBuilder: EventContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  abookServiceClient: ABookServiceClient,
  postOffice: LocalPostOffice)
    extends WebsiteController(actionAuthenticator) {

  private def createBasicUserInvitation(socialUser: SocialUserInfo, state: State[Invitation]): BasicUserInvitation = {
    BasicUserInvitation(name = socialUser.fullName, picture = socialUser.getPictureUrl(75, 75), state = state)
  }

  def invite = AuthenticatedHtmlAction { implicit request =>
    Redirect("/friends/invite") // Can't use reverse routes because we need to send to this URL exactly
  }

  private def CloseWindow() = Ok(Html("<script>window.close()</script>"))

  private val url = current.configuration.getString("application.baseUrl").get
  private val appId = current.configuration.getString("securesocial.facebook.clientId").get
  private def fbInviteUrl(invite: Invitation)(implicit session: RSession): String = {
    val identity = socialUserInfoRepo.get(invite.recipientSocialUserId.get)
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

      def sendEmailInvitation(c: EContact, invite:Invitation) {
        val path = routes.InviteController.acceptInvite(invite.externalId).url
        val messageWithUrl = s"${message getOrElse ""}\n$url$path"
        val electronicMail = ElectronicMail(
          senderUserId = None,
          from = EmailAddresses.INVITATION,
          fromName = Some("Kifi Team"),
          to = List(new EmailAddressHolder {
            override val address = c.email
          }),
          subject = subject.getOrElse(""),
          htmlBody = messageWithUrl,
          category = PostOffice.Categories.User.INVITATION)
        postOffice.sendMail(electronicMail)
        log.info(s"[inviteConnection-email] sent invitation to $c")
      }

      if(fullSocialId.size != 2) {
        CloseWindow()
      } else if (fullSocialId(0) == "email") {
        log.info(s"[inviteConnection-email] inviting: ${fullSocialId(1)}")
        val econtactOptF = abookServiceClient.getEContactByEmail(request.userId, fullSocialId(1))
        Async {
          econtactOptF.map { econtactOpt =>
            econtactOpt match {
              case Some(c) => {
                val inviteOpt = invitationRepo.getBySenderIdAndRecipientEContactId(request.userId, c.id.get)
                log.info(s"[inviteConnection-email] inviteOpt=$inviteOpt")
                inviteOpt match {
                  case Some(alreadyInvited) if alreadyInvited.state != InvitationStates.INACTIVE => {
                    sendEmailInvitation(c, alreadyInvited)
                  }
                  case inactiveOpt => {
                    val totalAllowedInvites = userValueRepo.getValue(request.user.id.get, "availableInvites").map(_.toInt).getOrElse(6)
                    val currentInvitations = invitationRepo.getByUser(request.user.id.get).filter(_.state != InvitationStates.INACTIVE)
                    if (currentInvitations.length < totalAllowedInvites) {
                      val invite = inactiveOpt map { _.copy(senderUserId = Some(request.user.id.get)) } getOrElse {
                        Invitation(
                          senderUserId = request.user.id,
                          recipientSocialUserId = None,
                          recipientEContactId = c.id,
                          state = InvitationStates.INACTIVE
                        )
                      }
                      sendEmailInvitation(c, invite)
                      invitationRepo.save(invite.withState(InvitationStates.ACTIVE))
                    }
                  }
                }
              }
              case None => {
                log.warn(s"[inviteConnection-email] cannot locate econtact entry for ${fullSocialId(1)}")
              }
            }
            CloseWindow()
          }
        }
      } else {
        val socialUserInfo = socialUserInfoRepo.get(SocialId(fullSocialId(1)), SocialNetworkType(fullSocialId(0)))
        invitationRepo.getByRecipientSocialUserId(socialUserInfo.id.get) match {
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
                Some(createBasicUserInvitation(socialUserRepo.get(s.recipientSocialUserId.get), s.state))
            }
            if (currentInvitations.length < totalAllowedInvites) {
              val invite = inactiveOpt map {
                _.copy(senderUserId = Some(request.user.id.get))
              } getOrElse Invitation(
                senderUserId = Some(request.user.id.get),
                recipientSocialUserId = socialUserInfo.id,
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

  def acceptInvite(id: ExternalId[Invitation]) = HtmlAction(allowPending = true)(authenticatedAction = { implicit request =>
    Redirect(com.keepit.controllers.core.routes.AuthController.signupPage)
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
            Redirect(com.keepit.controllers.core.routes.AuthController.signupPage).withCookies(Cookie("inv", invite.externalId.id))
          } else {
            Async {
              invite.recipientSocialUserId.map { recipientSocialUserId =>
                Promise.successful(Option(socialUserInfoRepo.get(invitation.get.recipientSocialUserId.get).fullName)).future
              }.getOrElse {
                invite.recipientEContactId.map { econtactId =>
                  abookServiceClient.getEContactById(econtactId).map { cOpt => cOpt.map(_.name) }
                }.getOrElse(Promise.successful(None).future)
              }.map {
                case Some(name) =>
                  Ok(views.html.auth.auth("signup", titleText = s"${name}, join ${inviterUserOpt.get.firstName} on Kifi!", titleDesc = s"Kifi is in beta and accepting users on invitations only. Click here to accept ${inviterUserOpt.get.firstName}'s invite."))
                case None =>
                  log.warn(s"[acceptInvite] invitation record $invite has neither recipient social id or econtact id")
                  Redirect(com.keepit.controllers.core.routes.AuthController.signupPage)
              }
            }
          }
        case _ =>
          Redirect(com.keepit.controllers.core.routes.AuthController.signupPage)
      }
  })


  def confirmInvite(id: ExternalId[Invitation], errorMsg: Option[String], errorCode: Option[Int]) = Action {
    db.readWrite { implicit session =>
      val invitation = invitationRepo.getOpt(id)
      invitation match {
        case Some(invite) =>
          if (errorCode.isEmpty) {
            invitationRepo.save(invite.copy(state = InvitationStates.ACTIVE))
            SafeFuture{
              val contextBuilder = eventContextBuilder()
              contextBuilder += ("invitee", invite.recipientSocialUserId.getOrElse(invite.recipientEContactId.get).id)
              heimdal.trackEvent(UserEvent(invite.senderUserId.map(_.id).getOrElse(-1), contextBuilder.build, EventType("invite_sent")))
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
