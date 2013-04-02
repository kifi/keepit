package com.keepit.controllers.admin

import play.api.mvc.{Action, Controller}
import play.api.i18n.Messages
import securesocial.core._
import play.api.{Play, Logger}
import Play.current
import play.api.mvc.{CookieBaker, Session}
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.json.Json
import com.keepit.common.db._
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.social.{SocialId, SocialNetworks}
import com.keepit.common.net._
import com.keepit.model._
import com.keepit.common.healthcheck._
import com.keepit.common.db.slick._

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton}

@Singleton
class AdminAuthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo)
    extends AdminController(actionAuthenticator) {

  def unimpersonate = AdminJsonAction { request =>
    Ok(Json.obj("userId" -> request.userId.toString)).discardingCookies(ImpersonateCookie.discard)
  }

  def impersonate(id: Id[User]) = AdminJsonAction { request =>
    val user = db.readOnly { implicit s =>
      userRepo.get(id)
    }
    log.info("impersonating user %s".format(user)) //todo(eishay) add event & email
    Ok(Json.obj("userId" -> id.toString)).withCookies(ImpersonateCookie.encodeAsCookie(Some(user.externalId)))
  }
}
