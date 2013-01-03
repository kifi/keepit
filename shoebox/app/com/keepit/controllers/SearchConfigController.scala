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
import play.api.libs.json.{Json, JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue}
import com.keepit.inject._
import com.keepit.common.time._
import com.keepit.common.net._
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.serializer.UserWithSocialSerializer._
import com.keepit.serializer.UserWithSocialSerializer
import com.keepit.serializer.BasicUserSerializer
import com.keepit.controllers.CommonActions._
import play.api.http.ContentTypes
import securesocial.core._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.social._
import com.keepit.common.social.UserWithSocial.toUserWithSocial
import com.keepit.common.controller.FortyTwoController
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.graph.URIGraph
import views.html.defaultpages.unauthorized
import org.joda.time.LocalDate
import scala.collection.immutable.Map
import play.api.libs.json.JsArray
import com.keepit.search.SearchConfig
import com.keepit.search.SearchConfigManager

object SearchConfigController extends FortyTwoController {
  def showUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val user = CX.withConnection { implicit conn =>
      UserWithSocial.toUserWithSocial(User.get(userId))
    }
    Ok(views.html.searchConfig(user))
  }

  def setUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("whoops")
    }

    val configManager = inject[SearchConfigManager]
    val config = configManager.getUserConfig(userId)
    configManager.setUserConfig(userId, config(form))
    Redirect(com.keepit.controllers.routes.SearchConfigController.showUserConfig(userId))
  }

  def resetUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val configManager = inject[SearchConfigManager]
    configManager.resetUserConfig(userId)
    Redirect(com.keepit.controllers.routes.SearchConfigController.showUserConfig(userId))
  }

  def allConfigParams(userId: Id[User]): Seq[(String, String)] = {
    val configManager = inject[SearchConfigManager]
    configManager.getUserConfig(userId).iterator.toSeq.sortBy(_._1)
  }
}
