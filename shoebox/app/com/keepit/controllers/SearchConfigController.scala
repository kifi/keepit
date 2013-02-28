package com.keepit.controllers

import scala.math.BigDecimal.RoundingMode

import com.keepit.common.analytics.reports.{Report, DailyDustSettledKifiHadResultsByExperiment, DailyKifiResultClickedByExperiment, DailyGoogleResultClickedByExperiment}
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
import scala.BigDecimal

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

  private def getChartData(reportA: Report, reportB: Report, minDays: Int = 10, maxDays: Int = 20) = {
    val cdt = currentDateTime
    val completeReportA = reportA.get(cdt.minusDays(maxDays), cdt)
    val completeReportB = reportB.get(cdt.minusDays(maxDays), cdt)
    val aMap = completeReportA.list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val bMap = completeReportB.list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val now = currentDate
    val (days, diffs) = (for (i <- maxDays to 0 by -1) yield {
      val day = now.minusDays(i)
      val (a, b) = (aMap.get(day).getOrElse(0), bMap.get(day).getOrElse(0))
      val total = 1.0*(a + b)
      (day, if (total > 0) a / total else 0)
    }) match { case tuples =>
      val toDrop = tuples.indexWhere(_._2 != 0.0) min (tuples.length - minDays)
      val (days, kvg) = tuples.drop(toDrop).unzip
      (days, kvg.zip(0.0 +: kvg).map { case (a, b) => a - b })
    }
    JsObject(List(
      "day0" -> JsString(days.min.toString),
      "counts" -> JsArray(diffs.map {i => JsNumber(BigDecimal(i).setScale(3, RoundingMode.HALF_EVEN))})
    ))
  }

  def getKifiVsGoogle(experimentId: Id[SearchConfigExperiment]) = AuthenticatedJsonAction { implicit request =>
    val e = Some(experimentId).filter(_.id != 0).map(inject[SearchConfigManager].getExperiment)
    Ok(getChartData(new DailyKifiResultClickedByExperiment(e), new DailyGoogleResultClickedByExperiment(e)))
  }

  def getKifiHadResults(expId: Id[SearchConfigExperiment]) = AuthenticatedJsonAction { implicit request =>
    val e = Some(expId).filter(_.id != 0).map(inject[SearchConfigManager].getExperiment)
    Ok(getChartData(
      new DailyDustSettledKifiHadResultsByExperiment(e, true),
      new DailyDustSettledKifiHadResultsByExperiment(e, false)))
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
