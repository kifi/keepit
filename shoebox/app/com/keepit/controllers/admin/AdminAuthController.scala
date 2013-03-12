package com.keepit.controllers.admin

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
import play.api.libs.json.Json
import com.keepit.common.controller.AdminController
import com.keepit.common.controller.FortyTwoController._
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialId, SocialNetworks}
import com.keepit.common.logging.Logging
import com.keepit.common.net._
import com.keepit.model._
import com.keepit.common.healthcheck._
import com.keepit.common.db.slick._

import com.google.inject.{Inject, Singleton}

@Singleton
class AdminAuthController @Inject() (
  db: Database, userRepo: UserRepo)
    extends AdminController {

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
