package com.keepit.controllers.website

import com.keepit.common.controller.WebsiteController
import com.keepit.common.logging.Logging
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.http.ContentTypes
import play.api.mvc._
import play.api._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.common.controller.ActionAuthenticator
import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.common.db.State
import com.keepit.common.social._
import play.api.libs.json._

case class BasicUserInvitation(name: String, picture: String, state: State[Invitation]) 

@Singleton
class HomeController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  invitationRepo: InvitationRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def home = HtmlAction(true)(authenticatedAction = { implicit request =>

    if(request.user.state == UserStates.PENDING) { pendingHome() }
    else {
      val userAgreedToTOS = db.readOnly(userValueRepo.getValue(request.user.id.get, "agreedToTOS")(_)).map(_.toBoolean).getOrElse(false)
      if(!userAgreedToTOS) {
        if(userIsAllowed(request.user, request.experimants)) {
          Redirect(routes.OnboardingController.tos())
        } else {
          Ok
        }
      } else {
        val friendsOnKifi = db.readOnly { implicit session =>
          socialConnectionRepo.getFortyTwoUserConnections(request.user.id.get).map { u =>
            val user = userRepo.get(u)
            if(user.state == UserStates.ACTIVE) Some(user.externalId)
            else None
          } flatten
        }
        
        if(userIsAllowed(request.user, request.experimants))
          Ok(views.html.website.userHome(request.user, friendsOnKifi))
        else
          Ok
      }
    }
  }, unauthenticatedAction = { implicit request =>
    Ok
    //Ok(views.html.website.welcome()) // disabled for now
  })
  
  def pendingHome()(implicit request: AuthenticatedRequest[AnyContent]) = {
    val user = request.user
    val name = s"${user.firstName} ${user.lastName}"
    val (email, friendsOnKifi) = db.readOnly { implicit session =>
      val email = emailRepo.getByUser(user.id.get).headOption.map(_.address)
      val friendsOnKifi = socialConnectionRepo.getFortyTwoUserConnections(user.id.get).map { u =>
        val user = userRepo.get(u)
        if(user.state == UserStates.ACTIVE) Some(user.externalId)
        else None
      } flatten
  
      (email, friendsOnKifi)
    }
    Ok(views.html.website.onboarding.userRequestReceived(user, email, friendsOnKifi))
  }

  def invite = AuthenticatedHtmlAction { implicit request =>
    val friendsOnKifi = db.readOnly { implicit session =>
      socialConnectionRepo.getFortyTwoUserConnections(request.user.id.get).map { u =>
        val user = userRepo.get(u)
        if(user.state == UserStates.ACTIVE) Some(user.externalId)
        else None
      } flatten
    }
    
    val (invites, invitesLeft, invitesSent, invitesAccepted) = db.readOnly { implicit session =>
      val totalAllowedInvites = userValueRepo.getValue(request.user.id.get, "availableInvites").map(_.toInt).getOrElse(6)
      val currentInvitations = invitationRepo.getByUser(request.user.id.get).map{ s => 
        val socialUser = socialUserRepo.get(s.recipientSocialUserId)
        Some(BasicUserInvitation(
          name = socialUser.fullName,
          picture = s"https://graph.facebook.com/${socialUser.socialId.id}/picture?type=square&width=75&height=75",
          state = s.state
        ))
      }
      val left = totalAllowedInvites - currentInvitations.length
      val sent = currentInvitations.length
      val accepted = currentInvitations.count( s => if(s.isDefined && s.get.state == InvitationStates.JOINED) true else false)
      val invites = currentInvitations ++ Seq.fill(left)(None)
      
      (invites, left, sent, accepted)
    }
    
    Ok(views.html.website.inviteFriends(request.user, friendsOnKifi, invites, invitesLeft, invitesSent, invitesAccepted))
  }
  
  def inviteConnection = AuthenticatedJsonToJsonAction { implicit request =>
    val fullSocialId = (request.body \ "socialId").as[String].split("/").toSeq
    db.readWrite { implicit session =>
      if(fullSocialId.size != 2) {
        BadRequest
      } else {
        val socialUserInfo = socialUserInfoRepo.get(SocialId(fullSocialId(1)), SocialNetworks.FACEBOOK)
        invitationRepo.getByRecipient(socialUserInfo.id.get) match {
          case Some(alreadyInvited) => BadRequest(Json.obj("invitation" -> "Already Invited"))
          case None =>
            val totalAllowedInvites = userValueRepo.getValue(request.user.id.get, "availableInvites").map(_.toInt).getOrElse(6)
            val currentInvitations = invitationRepo.getByUser(request.user.id.get).map{ s => 
              val socialUser = socialUserRepo.get(s.recipientSocialUserId)
              Some(BasicUserInvitation(
                name = socialUser.fullName,
                picture = s"https://graph.facebook.com/${socialUser.socialId.id}/picture?type=square&width=75&height=75",
                state = s.state
              ))
            }
            val left = totalAllowedInvites - currentInvitations.length
            val invites = currentInvitations ++ Seq.fill(left)(None)
            
            if(left > 0) {
              invitationRepo.save(Invitation(senderUserId = request.user.id.get, recipientSocialUserId = socialUserInfo.id.get))
              Ok(Json.obj("invitation" -> "success"))
            } else {
              BadRequest("No remaining invites")
            }
        }
      }
    }
    

  }

  def gettingStarted = AuthenticatedHtmlAction { implicit request =>
    
    Ok(views.html.website.gettingStarted(request.user))
  }
  
  // temporary during development:
  def userIsAllowed(user: User, experiments: Seq[State[ExperimentType]]) = {
    Play.isDev || experiments.contains("admin")
  }
  
  private def inviteFacebookUser(id: String) = {
    
  }


  def giveMeA200 = Action { request =>
    Ok("You got it!")
  }
}
