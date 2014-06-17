package com.keepit.controllers.admin

import play.api.libs.json.Json
import com.keepit.common.db._
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.model._
import com.keepit.common.db.slick._

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.common.mail.{SystemEmailAddress, ElectronicMail}
import com.keepit.common.healthcheck.SystemAdminMailSender

class AdminAuthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo,
  systemAdminMailSender: SystemAdminMailSender,
  impersonateCookie: ImpersonateCookie)
    extends AdminController(actionAuthenticator) {

  def unimpersonate = AdminJsonAction.authenticated { request =>
    Ok(Json.obj("userId" -> request.userId.toString)).discardingCookies(impersonateCookie.discard)
  }

  def impersonate(id: Id[User]) = AdminJsonAction.authenticated { request =>
    val user = db.readOnly { implicit s =>
      userRepo.get(id)
    }
    log.info(s"impersonating user $user")
    systemAdminMailSender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.ENG),
      subject =  s"${request.user.firstName} impersonating user $user",
      htmlBody = s"we know that ${request.user.firstName} ${request.user.lastName} is a good guy, won't abuse it",
      category = NotificationCategory.System.ADMIN))
    Ok(Json.obj("userId" -> id.toString)).withCookies(impersonateCookie.encodeAsCookie(Some(user.externalId)))
  }
}

