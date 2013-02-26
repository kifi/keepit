package com.keepit.controllers

import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.social._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search._

import play.api.Play.current
import play.api.libs.json.JsObject

object SearchConfigController extends FortyTwoController {
  def showUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val user = inject[DBConnection].readOnly { implicit s =>
      val repo = inject[UserWithSocialRepo]
      repo.toUserWithSocial(inject[UserRepo].get(userId))
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

  def getExperiments = AdminHtmlAction { implicit request =>
    val experiments = inject[SearchConfigManager].getExperiments
    val default = inject[SearchConfigManager].defaultConfig
    Ok(views.html.searchConfigExperiments(experiments, default.params))
  }

  def addNewExperiment = AdminHtmlAction { implicit request =>
    inject[SearchConfigManager].saveExperiment(
      SearchConfigExperiment(description = "New Experiment",
        config = inject[SearchConfigManager].defaultConfig.params))
    Redirect(com.keepit.controllers.routes.SearchConfigController.getExperiments)
  }

  def deleteExperiment = AuthenticatedJsonAction { implicit request =>
    val id = request.request.body.asFormUrlEncoded.get.mapValues(_.head)
       .get("id").map(_.toInt).map(Id[SearchConfigExperiment](_))
    id.map { id =>
      val experiment = inject[SearchConfigManager].getExperiment(id)
      inject[SearchConfigManager].saveExperiment(experiment.withState(SearchConfigExperimentStates.INACTIVE))
    }
    Ok(JsObject(Seq()))
  }

  def updateExperiment = AuthenticatedJsonAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded.get.mapValues(_.head)
    val id = form.get("id").map(_.toInt).map(Id[SearchConfigExperiment](_))
    val desc = form.get("description").getOrElse("")
    val weight = form.get("weight").map(_.toDouble).getOrElse(0.0)
    val state = form.get("state").collect {
      case "active" => SearchConfigExperimentStates.ACTIVE
      case "paused" => SearchConfigExperimentStates.PAUSED
    }
    val params = form.collect {
      case (k, v) if k.startsWith("param_") => k.split("_", 2)(1) -> v
    }.toMap
    val manager = inject[SearchConfigManager]
    id.map { id =>
      val exp = manager.getExperiment(id)
      manager.saveExperiment(exp.copy(description = desc, weight = weight, config = exp.config ++ params,
        state = state.getOrElse(exp.state)))
    }
    Ok(JsObject(Seq()))
  }
}
