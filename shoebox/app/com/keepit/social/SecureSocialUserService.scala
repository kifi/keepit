package com.keepit.social

import play.api.Application
import securesocial.core.{UserServicePlugin, UserId, SocialUser}
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.model._
import play.api.Play.current
import com.keepit.common.logging.Logging

class SecureSocialUserService(application: Application) extends UserServicePlugin(application) with Logging {

  /**
   * Assuming for now that there is only facebook
   */
  def find(id: UserId): Option[SocialUser] = 
    CX.withConnection { implicit conn =>
      User.getOpt(FacebookId(id.id))
    } match {
      case None => None
      case Some(user) => user.socialUser
    }
  

  def save(socialUser: SocialUser): Unit = 
    CX.withConnection { implicit conn => {
      val user = User.getOpt(FacebookId(socialUser.id.id))
      user match {
        case None => {
          log.info("could not find a user for facebookId [%s]. will create one".format(socialUser.id.id))
          createUser(socialUser)
        }
        case Some(us) => us.withSecureSocial(socialUser).save 
      }
    }    
  }
  
   def createUser(socialUser : SocialUser) = {
    CX.withConnection { implicit conn => {
      log.info("a new keepit ID was created to facebookId [%s]".format(socialUser.id.id))
      val user = User(firstName = socialUser.displayName, lastName = socialUser.displayName, 
          facebookId = FacebookId(socialUser.id.id)).withSecureSocial(socialUser)
      val returnedUser = user.save
      log.info("a new user was created [%s]".format(returnedUser))
      returnedUser
   }}
   }
}
