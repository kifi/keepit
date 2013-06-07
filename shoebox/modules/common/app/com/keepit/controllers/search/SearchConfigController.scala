package com.keepit.controllers.search

import scala.Some

import com.google.inject.{Inject, Singleton}
import com.keepit.common.analytics.MongoEventStore
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.search._

import play.api.libs.json.Json
import play.api.mvc.Action

@Singleton
class SearchConfigController @Inject() (
  configManager: SearchConfigManager,
  store: MongoEventStore)
    extends SearchServiceController {

  def getSearchDefaultConfig = Action{ request =>
    Ok(Json.toJson(configManager.defaultConfig.params))
  }

  def showUserConfig(userId: Id[User]) = Action { implicit request =>
    val configs = configManager.getUserConfig(userId).iterator.toSeq.sortBy(_._1)
    Ok//Ok(html.admin.searchConfig(user, configs))
  }

  def setUserConfig(userId: Id[User]) = Action { implicit request =>
    val params = request.body.asJson match {
      case Some(json) => Json.fromJson[Map[String, String]](json)
      case None => throw new Exception("whoops")
    }

    val config = configManager.getUserConfig(userId)
    configManager.setUserConfig(userId, config(params.get))
    Ok("config updated")
  }

  def resetUserConfig(userId: Id[User]) = Action { implicit request =>
    configManager.resetUserConfig(userId)
    Ok("config set to default")
  }
}
