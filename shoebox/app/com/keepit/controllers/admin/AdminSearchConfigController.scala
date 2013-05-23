package com.keepit.controllers.admin

import scala.Some
import scala.collection.concurrent
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import org.joda.time._
import com.google.inject.{Inject, Singleton}
import com.keepit.common.analytics.reports.{ReportRepo, DailyDustSettledKifiHadResultsByExperimentRepo, DailyKifiResultClickedByExperimentRepo, DailyGoogleResultClickedByExperimentRepo}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.model._
import com.keepit.search._
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import views.html
import com.keepit.common.analytics.MongoEventStore
import scala.concurrent.Await
import scala.concurrent.duration._

@Singleton
class AdminSearchConfigController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userWithSocialRepo: UserWithSocialRepo,
  userRepo: UserRepo,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  searchClient: SearchServiceClient,
  store: MongoEventStore
  )
    extends AdminController(actionAuthenticator) {

  def showUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    Async {
      searchClient.showUserConfig(userId).map{ html => Ok(html) }
    }
  }

  def setUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("whoops")
    }
    Async {
      searchClient.setUserConfig(userId, form).map{ r =>
        Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.showUserConfig(userId))
      }
    }
  }

  def resetUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    Async {
      searchClient.resetUserConfig(userId).map{ r =>
        Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.showUserConfig(userId))
      }
    }
  }

  def getExperiments = AdminHtmlAction { implicit request =>
    val experiments = db.readOnly { implicit s => searchConfigExperimentRepo.getNotInactive() }
    val default = Await.result(searchClient.getSearchDefaultConfig, 5 seconds)
    Ok(html.admin.searchConfigExperiments(experiments, default.params))
  }

  private val existingReportData = new concurrent.TrieMap[ReportRepo, (DateTime, Map[LocalDate, Int])]()
  private val refetchInterval = Minutes.minutes(10)
  private val expireInterval = Days.ONE
  private def refetchReportData(report: ReportRepo, endDate: DateTime, days: Int): Map[LocalDate, Int] = {
    val (lastDate, existingData) = existingReportData.get(report).getOrElse((START_OF_TIME, Map()))
    val completeReportData = report.get(Seq(endDate.minusDays(days), lastDate).max, endDate)
        .list.map(row => row.date.toLocalDate -> row.fields.head._2.value.toInt).toMap
    val data = (existingData ++ completeReportData).toMap
    existingReportData += report -> (endDate, data)
    data
  }
  private def getReportData(report: ReportRepo, endDate: DateTime, days: Int = 20): Map[LocalDate, Int] = {
    val (lastDate, existingData) = existingReportData.get(report).getOrElse((START_OF_TIME, Map()))
    if (lastDate.plus(expireInterval) isBefore endDate) {
      refetchReportData(report, endDate, days)
    } else {
      if (lastDate.plus(refetchInterval) isBefore endDate) {
        future { refetchReportData(report, endDate, days) }
      }
      existingData.asInstanceOf[Map[LocalDate, Int]]
    }
  }

  private def getChartData(
      reportA: Option[SearchConfigExperiment] => ReportRepo,
      reportB: Option[SearchConfigExperiment] => ReportRepo,
      experiment: Option[SearchConfigExperiment],
      minDays: Int = 10, maxDays: Int = 20) = {
    val now = currentDateTime
    val today = currentDate
    val aMap = getReportData(reportA(experiment), now)
    val bMap = getReportData(reportB(experiment), now)
    val aDefMap = getReportData(reportA(None), now)
    val bDefMap = getReportData(reportB(None), now)
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

  def getKifiVsGoogle(expId: Id[SearchConfigExperiment]) = AdminJsonAction { implicit request =>
    val e = Some(expId).filter(_.id > 0).map(db.readOnly { implicit s => searchConfigExperimentRepo.get(_) })
    val data = getChartData(new DailyKifiResultClickedByExperimentRepo(store, _), new DailyGoogleResultClickedByExperimentRepo(store, _), e)
    Ok(data)
  }

  def getKifiHadResults(expId: Id[SearchConfigExperiment]) = AdminJsonAction { implicit request =>
    val e = Some(expId).filter(_.id > 0).map(db.readOnly { implicit s => searchConfigExperimentRepo.get(_) })
    val data = getChartData(new DailyDustSettledKifiHadResultsByExperimentRepo(store, _, true),
      new DailyDustSettledKifiHadResultsByExperimentRepo(store, _, false), e)
    Ok(data)
  }

  def addNewExperiment = AdminHtmlAction { implicit request =>
    val defaultConfig =  Await.result(searchClient.getSearchDefaultConfig, 5 seconds)
    db.readWrite { implicit s => searchConfigExperimentRepo.save(SearchConfigExperiment(description = "New Experiment", config = defaultConfig)) }
    Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.getExperiments)
  }

  def deleteExperiment = AdminJsonAction { implicit request =>
    val id = request.request.body.asFormUrlEncoded.get.mapValues(_.head)
       .get("id").map(_.toInt).map(Id[SearchConfigExperiment](_))
    id.map { id =>
      val experiment = db.readOnly { implicit s => searchConfigExperimentRepo.get(id) }
      db.readWrite { implicit s => searchConfigExperimentRepo.save(experiment.withState(SearchConfigExperimentStates.INACTIVE)) }
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
      val exp = db.readOnly { implicit s => searchConfigExperimentRepo.get(id) }
      val toSave = exp.copy(description = desc, weight = weight, config = exp.config(params))
          .withState(state.getOrElse(exp.state))
       db.readWrite { implicit s => searchConfigExperimentRepo.save(toSave) }
    }
    Ok(JsObject(Seq()))
  }
}
