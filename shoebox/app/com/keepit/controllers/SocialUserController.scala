package com.keepit.controllers

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
import com.keepit.inject._
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
import com.keepit.common.controller.FortyTwoController

object SocialUserController extends FortyTwoController {

  def resetSocialUser(socialUserId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val socialUserInfo = inject[Database].readWrite { implicit s =>
      val repo = inject[SocialUserInfoRepo]
      repo.save(repo.get(socialUserId).reset())
    }
    Redirect(com.keepit.controllers.routes.SocialUserController.socialUserView(socialUserInfo.id.get))
  }

  def socialUserView(socialUserId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>

    val (socialUserInfo, socialConnections) = inject[Database].readOnly { implicit s =>
      val socialRepo = inject[SocialUserInfoRepo]
      val connectionRepo = inject[SocialConnectionRepo]
      val socialUserInfo = socialRepo.get(socialUserId)
      val socialConnections = connectionRepo.getSocialUserConnections(socialUserId).sortWith((a,b) => a.fullName < b.fullName)

      (socialUserInfo, socialConnections)
    }

    val rawInfo = inject[SocialUserRawInfoStore].get(socialUserInfo.id.get)

    Ok(views.html.socialUser(socialUserInfo, socialConnections, rawInfo))
  }

  def socialUsersView(page: Int) = AdminHtmlAction { implicit request =>
    val PAGE_SIZE = 300
    val (socialUsers, count) = inject[Database].readOnly { implicit s =>
      val repo = inject[SocialUserInfoRepo]
      (repo.page(page, PAGE_SIZE), repo.count)
    }
    val pageCount = (count / PAGE_SIZE + 1).toInt
    Ok(views.html.socialUsers(socialUsers, page, count, pageCount))
  }

  def refreshSocialInfo(socialUserInfoId: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val graph = inject[SocialGraphPlugin]
    val socialUserInfo = inject[Database].readOnly { implicit s => inject[SocialUserInfoRepo].get(socialUserInfoId) }
    if (socialUserInfo.credentials.isEmpty) throw new Exception("can't fetch user info for user with missing credentials: %s".format(socialUserInfo))
    graph.asyncFetch(socialUserInfo)
    Redirect(com.keepit.controllers.routes.SocialUserController.socialUserView(socialUserInfoId))
  }
}
