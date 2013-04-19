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
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.DBSession.RSession

@Singleton
class HomeController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def home = HtmlAction(true)(authenticatedAction = { implicit request =>

    if(request.user.state == UserStates.PENDING) { pendingHome() }
    else {
      val friendsOnKifi = db.readOnly { implicit session =>
        socialConnectionRepo.getFortyTwoUserConnections(request.user.id.get).map { u =>
          val user = userRepo.get(u)
          if(user.state == UserStates.ACTIVE) Some(user.externalId)
          else None
        } flatten
      }
      
      Ok(views.html.website.userHome(request.user, friendsOnKifi))
    }
  }, unauthenticatedAction = { implicit request =>
    Ok(views.html.website.welcome())
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

  def install = AuthenticatedHtmlAction { implicit request =>
    db.readWrite { implicit session =>
      socialUserRepo.getByUser(request.user.id.get) map { su =>
        invitationRepo.getByRecipient(su.id.get) match {
          case Some(invite) =>
            invitationRepo.save(invite.withState(InvitationStates.JOINED))
        }
      }
    }
    Ok(views.html.website.install(request.user))
  }
  
  def gettingStarted = AuthenticatedHtmlAction { implicit request =>
    
    Ok(views.html.website.gettingStarted(request.user))
  }
  
  // temporary during development:
  def userIsAllowed(user: User, experiments: Seq[State[ExperimentType]]) = {
    Play.isDev || experiments.contains(ExperimentTypes.ADMIN)
  }
}
