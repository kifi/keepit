package com.keepit.controllers.search

import scala.Some
import scala.collection.concurrent
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import org.joda.time._
import com.google.inject.{Inject, Singleton}
import com.keepit.common.analytics.reports.{ReportRepo, DailyDustSettledKifiHadResultsByExperimentRepo, DailyKifiResultClickedByExperimentRepo, DailyGoogleResultClickedByExperimentRepo}
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.model._
import com.keepit.search._
import play.api.libs.json.Json
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.mvc.Action
import views.html
import com.keepit.common.analytics.MongoEventStore

@Singleton
class SearchConfigController @Inject() (
  configManager: SearchConfigManager,
  store: MongoEventStore)
    extends SearchServiceController {

  def getSearchDefaultConfig = Action{ request =>
    Ok(Json.toJson(configManager.defaultConfig.params))
  }

  def showUserConfig(userId: Id[User]) = Action { implicit request =>
    /*val user = db.readOnly { implicit s =>
      userWithSocialRepo.toUserWithSocial(userRepo.get(userId))
    }*/
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
