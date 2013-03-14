package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import play.api.Play.current
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.model._
import com.keepit.serializer.UserWithSocialSerializer._
import com.keepit.serializer.UserWithSocialSerializer

import play.api.http.ContentTypes
import securesocial.core._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.social.{SocialGraphPlugin, UserWithSocial}
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.common.controller.AdminController
import views.html

import com.google.inject.{Inject, Singleton}

@Singleton
class AdminSocialUserController @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserRawInfoStore: SocialUserRawInfoStore,
  socialGraphPlugin: SocialGraphPlugin)
    extends AdminController {

  def resetSocialUser(socialUserId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val socialUserInfo = db.readWrite { implicit s =>
      socialUserInfoRepo.save(socialUserInfoRepo.get(socialUserId).reset())
    }
    Redirect(com.keepit.controllers.admin.routes.AdminSocialUserController.socialUserView(socialUserInfo.id.get))
  }

  def socialUserView(socialUserId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>

    val (socialUserInfo, socialConnections) = db.readOnly { implicit s =>
      val socialUserInfo = socialUserInfoRepo.get(socialUserId)
      val socialConnections = socialConnectionRepo.getSocialUserConnections(socialUserId).sortWith((a,b) => a.fullName < b.fullName)

      (socialUserInfo, socialConnections)
    }

    val rawInfo = socialUserRawInfoStore.get(socialUserInfo.id.get)

    Ok(html.admin.socialUser(socialUserInfo, socialConnections, rawInfo))
  }

  def socialUsersView(page: Int) = AdminHtmlAction { implicit request =>
    val PAGE_SIZE = 300
    val (socialUsers, count) = db.readOnly { implicit s =>
      (socialUserInfoRepo.page(page, PAGE_SIZE), socialUserInfoRepo.count)
    }
    val pageCount = (count / PAGE_SIZE + 1).toInt
    Ok(html.admin.socialUsers(socialUsers, page, count, pageCount))
  }

  def refreshSocialInfo(socialUserInfoId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val socialUserInfo = db.readOnly { implicit s => socialUserInfoRepo.get(socialUserInfoId) }
    if (socialUserInfo.credentials.isEmpty) throw new Exception("can't fetch user info for user with missing credentials: %s".format(socialUserInfo))
    socialGraphPlugin.asyncFetch(socialUserInfo)
    Redirect(com.keepit.controllers.admin.routes.AdminSocialUserController.socialUserView(socialUserInfoId))
  }
}
