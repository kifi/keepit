package com.keepit.controllers

import scala.math.BigDecimal.RoundingMode

import com.keepit.common.analytics.reports.{DailyKifiResultClickedByExperiment, DailyGoogleResultClickedByExperiment}
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search._

import play.api.Play.current
import play.api.libs.json.{JsString, JsArray, JsNumber, JsObject}

object SearchConfigController extends FortyTwoController {
  def showUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val user = inject[Database].readOnly { implicit s =>
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

  def getKifiVsGoogle(experimentId: Id[SearchConfigExperiment]) = AuthenticatedJsonAction { implicit request =>
    val e = Some(experimentId).filter(_.id != 0).map(inject[SearchConfigManager].getExperiment)
    val daysAgo = 30
    val cdt = currentDateTime
    val googleReport = new DailyGoogleResultClickedByExperiment(e).get(cdt.minusDays(daysAgo), cdt)
    val kifiReport = new DailyKifiResultClickedByExperiment(e).get(cdt.minusDays(daysAgo), cdt)
    val googleMap = googleReport.list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val kifiMap = kifiReport.list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val now = currentDate
    val (days, kvgDiffs) = (for (i <- daysAgo to 0 by -1) yield {
      val day = now.minusDays(i)
      val (kifiClicks, googleClicks) = (kifiMap.get(day).getOrElse(0), googleMap.get(day).getOrElse(0))
      val total = 1.0*(kifiClicks + googleClicks)
      (day, if (total > 0) kifiClicks / total else 0)
    }) match {
      case tuples =>
        val minLength = 10
        val toDrop = tuples.indexWhere(_._2 != 0.0) min (tuples.length - minLength)
        val (days, kvg) = tuples.drop(toDrop).unzip
        (days, kvg.zip(0.0 +: kvg).map { case (a, b) => a - b })
    }
    Ok(JsObject(List(
      "day0" -> JsString(days.min.toString),
      "counts" -> JsArray(kvgDiffs.map {i => JsNumber(BigDecimal(i).setScale(3, RoundingMode.HALF_EVEN))})
    )))
  }

  def addNewExperiment = AdminHtmlAction { implicit request =>
    inject[SearchConfigManager].saveExperiment(
      SearchConfigExperiment(description = "New Experiment", config = inject[SearchConfigManager].defaultConfig))
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
      manager.saveExperiment(exp.copy(description = desc, weight = weight, config = exp.config(params),
        state = state.getOrElse(exp.state)))
    }
    Ok(JsObject(Seq()))
  }
}
