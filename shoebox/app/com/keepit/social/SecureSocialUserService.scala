package com.keepit.social

import play.api.Application
import securesocial.core.{UserServicePlugin, UserId, SocialUser}
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.model._
import play.api.Play.current

class SecureSocialUserService(application: Application) extends UserServicePlugin(application) {

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
  

  def save(user: SocialUser): Unit = 
    CX.withConnection { implicit conn =>
      User.getOpt(FacebookId(user.id.id)).map { u =>
        u.withSecureSocial(user).save
      }
    }    
  
}
