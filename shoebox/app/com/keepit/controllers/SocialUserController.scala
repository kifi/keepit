package com.keepit.controllers

import play.api.data._
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api._
import play.api.Play.current
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
import com.keepit.inject._
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.model.{User, UserCxRepo}
import com.keepit.model._
import com.keepit.serializer.UserWithSocialSerializer._
import com.keepit.serializer.UserWithSocialSerializer
import com.keepit.controllers.CommonActions._
import play.api.http.ContentTypes
import securesocial.core._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.social.{SocialGraphPlugin, UserWithSocial}
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.common.controller.FortyTwoController

object SocialUserController extends FortyTwoController {

  def resetSocialUser(socialUserId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val socialUserInfo = CX.withConnection { implicit conn =>
      SocialUserInfoCxRepo.get(socialUserId).reset().save
    }
    Redirect(com.keepit.controllers.routes.SocialUserController.socialUserView(socialUserInfo.id.get))
  }

  def socialUserView(socialUserId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>

    val (socialUserInfo, socialConnections) = CX.withConnection { implicit conn =>
      val socialUserInfo = SocialUserInfoCxRepo.get(socialUserId)
      val socialConnections = SocialConnectionCxRepo.getSocialUserConnections(socialUserId).sortWith((a,b) => a.fullName < b.fullName)

      (socialUserInfo, socialConnections)
    }

    val rawInfo = inject[SocialUserRawInfoStore].get(socialUserInfo.id.get)

    Ok(views.html.socialUser(socialUserInfo, socialConnections, rawInfo))
  }

  def socialUsersView(page: Int) = AdminHtmlAction { implicit request =>
    val PAGE_SIZE = 300
    val (socialUsers, count) = CX.withConnection { implicit c =>
      (SocialUserInfoCxRepo.page(page, PAGE_SIZE), SocialUserInfoCxRepo.count)
    }
    val pageCount = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.socialUsers(socialUsers, page, count, pageCount))
  }

  def refreshSocialInfo(socialUserInfoId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val graph = inject[SocialGraphPlugin]
    val socialUserInfo = CX.withConnection { implicit conn => SocialUserInfoCxRepo.get(socialUserInfoId) }
    if (socialUserInfo.credentials.isEmpty) throw new Exception("can't fetch user info for user with missing credentials: %s".format(socialUserInfo))
    graph.asyncFetch(socialUserInfo)
    Redirect(com.keepit.controllers.routes.SocialUserController.socialUserView(socialUserInfoId))
  }
}
