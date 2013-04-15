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

@Singleton
class HomeController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  emailRepo: EmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def home = HtmlAction(true)(authenticatedAction = { implicit request =>

    if(request.user.state == UserStates.PENDING) { pendingHome() }
    else {
      val userAgreedToTOS = db.readOnly(userValueRepo.getValue(request.user.id.get, "agreedToTOS")(_)).map(_.toBoolean).getOrElse(false)
      if(!userAgreedToTOS) {
        Ok
        //Redirect(routes.OnboardingController.tos()) // disabled for now
      } else {
        val friendsOnKifi = db.readOnly { implicit session =>
          socialConnectionRepo.getFortyTwoUserConnections(request.user.id.get).map { u =>
            val user = userRepo.get(u)
            if(user.state == UserStates.ACTIVE) Some(user.externalId)
            else None
          } flatten
        }
        // Admin only for now
        if(request.experimants.contains("admin"))
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
      val email = emailRepo.getByUser(user.id.get).headOption.map(_.address).getOrElse("you@email.com")
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
    Ok(views.html.website.inviteFriends(request.user, friendsOnKifi))
  }

  def gettingStarted = AuthenticatedHtmlAction { implicit request =>
    
    Ok(views.html.website.gettingStarted(request.user))
  }


  def giveMeA200 = Action { request =>
    Ok("You got it!")
  }
}
