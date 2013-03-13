package com.keepit.controllers.admin

import java.util.concurrent.TimeUnit

import com.google.common.cache.{CacheLoader, CacheBuilder}
import com.keepit.common.analytics.reports.{Report, DailyDustSettledKifiHadResultsByExperiment, DailyKifiResultClickedByExperiment, DailyGoogleResultClickedByExperiment}
import com.keepit.common.controller.AdminController
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search._

import play.api.Play.current
import play.api.libs.json.{JsString, JsArray, JsNumber, JsObject}
import views.html

import com.google.inject.{Inject, Singleton}

@Singleton
class AdminSearchConfigController @Inject() (
  db: Database,
  userWithSocialRepo: UserWithSocialRepo,
  userRepo: UserRepo,
  configManager: SearchConfigManager)
    extends AdminController {

  def showUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val user = db.readOnly { implicit s =>
      userWithSocialRepo.toUserWithSocial(userRepo.get(userId))
    }
    val configs = configManager.getUserConfig(userId).iterator.toSeq.sortBy(_._1)
    Ok(html.admin.searchConfig(user, configs))
  }

  def setUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("whoops")
    }

    val config = configManager.getUserConfig(userId)
    configManager.setUserConfig(userId, config(form))
    Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.showUserConfig(userId))
  }

  def resetUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    configManager.resetUserConfig(userId)
    Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.showUserConfig(userId))
  }

  def allConfigParams(userId: Id[User]): Seq[(String, String)] =
    configManager.getUserConfig(userId).iterator.toSeq.sortBy(_._1)

  def getExperiments = AdminHtmlAction { implicit request =>
    val experiments = configManager.getExperiments
    val default = configManager.defaultConfig
    Ok(html.admin.searchConfigExperiments(experiments, default.params))
  }

  private def getChartData(
      reportA: Option[SearchConfigExperiment] => Report,
      reportB: Option[SearchConfigExperiment] => Report,
      experiment: Option[SearchConfigExperiment],
      minDays: Int = 10, maxDays: Int = 20) = {
    val now = currentDateTime
    val today = now.toLocalDate
    val completeReportA = reportA(experiment).get(now.minusDays(maxDays), now)
    val completeReportB = reportB(experiment).get(now.minusDays(maxDays), now)
    val completeReportADefault = reportA(None).get(now.minusDays(maxDays), now)
    val completeReportBDefault = reportB(None).get(now.minusDays(maxDays), now)
    val aMap = completeReportA.list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val bMap = completeReportB.list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val aDefMap = completeReportADefault.list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val bDefMap = completeReportBDefault.list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val (days, (as, bs, values), (adefs, bdefs, defaultValues)) = (for (i <- maxDays to 0 by -1) yield {
      val day = today.minusDays(i)
      val (a, b) = (aMap.get(day).getOrElse(0), bMap.get(day).getOrElse(0))
      val (adef, bdef) = (aDefMap.get(day).getOrElse(0), bDefMap.get(day).getOrElse(0))
      val total = 1.0*(a + b)
      val totalDef = 1.0*(adef + bdef)
      (day, (a, b, if (total > 0) a / total else 0), (adef, bdef, if (totalDef > 0) adef / totalDef else 0))
    }) match { case tuples =>
      val toDrop = tuples.indexWhere(_._2._3 != 0.0) min (tuples.length - minDays)
      tuples.drop(toDrop).unzip3 match { case (a, b, c) => (a, b.unzip3, c.unzip3) }
    }
    val (aSum, bSum, aDefSum, bDefSum) = (as.sum, bs.sum, adefs.sum, bdefs.sum)
    val (total, totalDef) = (1.0*(aSum + bSum), 1.0*(aDefSum + bDefSum))
    JsObject(List(
      "day0" -> JsString(days.min.toString),
      "counts" -> JsArray(values.map(JsNumber(_))),
      "defaultCounts" -> JsArray(defaultValues.map {i => JsNumber(i) }),
      "samples" -> JsArray((as, bs).zipped.map(_ + _).map(JsNumber(_)).toSeq),
      "defaultSamples" -> JsArray((adefs, bdefs).zipped.map(_ + _).map(JsNumber(_)).toSeq),
      "avg" -> JsNumber(if (total > 0) aSum / total else 0),
      "defaultAvg" -> JsNumber(if (totalDef > 0) aDefSum / totalDef else 0)
    ))
  }

  private lazy val kifiVsGoogleCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS)
    .build(new CacheLoader[Id[SearchConfigExperiment], JsObject] {
      def load(expId: Id[SearchConfigExperiment]) = {
        val e = Some(expId).filter(_.id > 0).map(configManager.getExperiment)
        getChartData(new DailyKifiResultClickedByExperiment(_), new DailyGoogleResultClickedByExperiment(_), e)
      }
    })
  private lazy val kifiHadResultsCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS)
    .build(new CacheLoader[Id[SearchConfigExperiment], JsObject] {
      def load(expId: Id[SearchConfigExperiment]) = {
        val e = Some(expId).filter(_.id > 0).map(configManager.getExperiment)
        getChartData(new DailyDustSettledKifiHadResultsByExperiment(_, true),
          new DailyDustSettledKifiHadResultsByExperiment(_, false), e)
      }
    })

  def getKifiVsGoogle(expId: Id[SearchConfigExperiment]) = AdminJsonAction { implicit request =>
    Ok(kifiVsGoogleCache(expId))
  }

  def getKifiHadResults(expId: Id[SearchConfigExperiment]) = AdminJsonAction { implicit request =>
    Ok(kifiHadResultsCache(expId))
  }

  def addNewExperiment = AdminHtmlAction { implicit request =>
    configManager.saveExperiment(
      SearchConfigExperiment(description = "New Experiment", config = configManager.defaultConfig))
    Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.getExperiments)
  }

  def deleteExperiment = AdminJsonAction { implicit request =>
    val id = request.request.body.asFormUrlEncoded.get.mapValues(_.head)
       .get("id").map(_.toInt).map(Id[SearchConfigExperiment](_))
    id.map { id =>
      val experiment = configManager.getExperiment(id)
      configManager.saveExperiment(experiment.withState(SearchConfigExperimentStates.INACTIVE))
    }
    Ok(JsObject(Seq()))
  }

  def updateExperiment = AdminJsonAction { implicit request =>
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
    id.map { id =>
      val exp = configManager.getExperiment(id)
      configManager.saveExperiment(exp.copy(description = desc, weight = weight, config = exp.config(params))
          .withState(state.getOrElse(exp.state)))
    }
    Ok(JsObject(Seq()))
  }
}
