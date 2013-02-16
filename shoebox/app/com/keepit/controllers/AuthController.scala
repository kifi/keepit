package com.keepit.controllers

/**
 * Copyright 2012 Jorge Aliss (jaliss at gmail dot com) - twitter: @jaliss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import play.api.mvc.{Action, Controller}
import play.api.i18n.Messages
import securesocial.core._
import play.api.{Play, Logger}
import Play.current
import play.api.mvc.{CookieBaker, Session}
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.controller.FortyTwoController._
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialId, SocialNetworks}
import com.keepit.common.logging.Logging
import com.keepit.common.net._
import com.keepit.model._
import com.keepit.common.controller.FortyTwoController
import com.keepit.inject._
import com.keepit.common.healthcheck._
import com.keepit.common.db.slick._

object AuthController extends FortyTwoController {
  def start = AuthenticatedJsonAction { implicit request =>
    val socialUser = request.socialUser
    log.info("facebook id %s".format(socialUser.id))
    val (userAgent, version, installationIdOpt) = request.body.asJson.map { json =>
      (UserAgent((json \ "agent").as[String]),
       KifiVersion((json \ "version").as[String]),
       (json \ "installation").asOpt[String].flatMap { id =>
         val kiId = ExternalId.asOpt[KifiInstallation](id)
         kiId match {
           case Some(_) =>
           case None =>
             // They sent an invalid id. Bug on client side?
             inject[HealthcheckPlugin].addError(HealthcheckError(
               method = Some(request.method.toUpperCase()),
               path = Some(request.path),
               callType = Healthcheck.API,
               errorMessage = Some("Invalid ExternalId passed in \"%s\" for userId %s".format(id, request.userId))))
         }
         kiId
       })}.get
    val (user, installation, sliderRuleGroup) = inject[DBConnection].readWrite{implicit s =>
      val repo = inject[KifiInstallationRepo]
      log.info("start. details: %s, %s, %s".format(userAgent, version, installationIdOpt))
      val installation: KifiInstallation = installationIdOpt flatMap { id =>
        repo.getOpt(request.userId, id)
      } match {
        case None =>
          repo.save(KifiInstallation(userId = request.userId, userAgent = userAgent, version = version))
        case Some(install) =>
          if (install.version != version || install.userAgent != userAgent) {
            repo.save(install.withUserAgent(userAgent).withVersion(version))
          } else {
            install
          }
      }

      (inject[UserRepo].get(request.userId), installation, inject[SliderRuleRepo].getGroup("default"))
    }

    Ok(JsObject(Seq(
      "avatarUrl" -> JsString(socialUser.avatarUrl.get),
      "name" -> JsString(socialUser.displayName),
      "facebookId" -> JsString(socialUser.id.id),
      "provider" -> JsString(socialUser.id.providerId),
      "userId" -> JsString(user.externalId.id),
      "installationId" -> JsString(installation.externalId.id),
      "rules" -> sliderRuleGroup.compactJson)))
    .withCookies(KifiInstallationCookie.encodeAsCookie(Some(installation.externalId)))
  }

  // where SecureSocial sends users if it can't figure out the right place (see securesocial.conf)
  def welcome = SecuredAction() { implicit request =>
    log.debug("in welcome. with user : [ %s ]".format(request.user ))
    Redirect(com.keepit.controllers.routes.HomeController.home())
  }

  def logOut = UserAwareAction { implicit request =>
    Ok(views.html.logOut(request.user)).withNewSession
  }

  def whois = AuthenticatedJsonAction { request =>
    val user = inject[DBConnection].readOnly(implicit s => inject[UserRepo].get(request.userId))
    Ok(JsObject(Seq("externalUserId" -> JsString(user.externalId.toString))))
  }

  def unimpersonate = AdminJsonAction { request =>
    Ok(JsObject(Seq("userId" -> JsString(request.userId.toString)))).discardingCookies(ImpersonateCookie.COOKIE_NAME)
  }

  def impersonate(id: Id[User]) = AdminJsonAction { request =>
    val user = inject[DBConnection].readOnly { implicit s =>
      inject[UserRepo].get(id)
    }
    log.info("impersonating user %s".format(user)) //todo(eishay) add event & email
    Ok(JsObject(Seq("userId" -> JsString(id.toString)))).withCookies(ImpersonateCookie.encodeAsCookie(Some(user.externalId)))
  }
}
