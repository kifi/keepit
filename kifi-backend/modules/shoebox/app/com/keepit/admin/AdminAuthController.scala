package com.keepit.controllers.admin

import play.api.libs.json.Json
import com.keepit.common.db._
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.model._
import com.keepit.common.db.slick._

import com.keepit.common.controller._
import com.google.inject.Inject
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.common.healthcheck.SystemAdminMailSender

class AdminAuthController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  userRepo: UserRepo,
  systemAdminMailSender: SystemAdminMailSender,
  impersonateCookie: ImpersonateCookie)
    extends AdminUserActions {

  def unimpersonate = AdminUserAction { request =>
    Ok(Json.obj("userId" -> request.userId.toString)).discardingCookies(impersonateCookie.discard)
  }

  def impersonate(id: Id[User]) = AdminUserAction { request =>
    val user = db.readOnlyMaster { implicit s =>
      userRepo.get(id)
    }
    val existingImp = impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))
    if (existingImp.isDefined) {
      throw new Exception(s"user ${request.user.firstName} ${request.user.lastName} is trying to impersonate user $id ${user.firstName} ${user.lastName} while the cookie is still in use to impersonate user $existingImp")
    }
    log.info(s"impersonating user $user")
    systemAdminMailSender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.ENG),
      subject = s"${request.user.firstName} impersonating user $user",
      htmlBody = s"we know that ${request.user.firstName} ${request.user.lastName} is a good person, won't abuse it",
      category = NotificationCategory.System.ADMIN))
    Ok(Json.obj("userId" -> id.toString)).withCookies(impersonateCookie.encodeAsCookie(Some(user.externalId)))
  }
}

