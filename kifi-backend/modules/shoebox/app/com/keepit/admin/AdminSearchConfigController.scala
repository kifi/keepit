package com.keepit.controllers.admin

import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.search._

import play.api.libs.json._
import views.html
import com.keepit.heimdal.HeimdalServiceClient
import scala.Predef._
import scala.collection.mutable.{ListBuffer, Map => MutableMap}

class AdminSearchConfigController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo,
  searchConfigExperimentRepo: SearchConfigExperimentRepo,
  searchClient: SearchServiceClient,
  heimdal: HeimdalServiceClient
  )
    extends AdminController(actionAuthenticator) {

  def showUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val searchConfigFuture = searchClient.showUserConfig(userId)
    val user = db.readOnly{ implicit s => userRepo.get(userId) }
    val searchConfig = Await.result(searchConfigFuture, 5 seconds)
    Ok(views.html.admin.searchConfig(user, searchConfig.iterator.toSeq.sortBy(_._1)))
  }

  def setUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("whoops")
    }
    searchClient.setUserConfig(userId, form)
    Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.showUserConfig(userId))
  }

  def resetUserConfig(userId: Id[User]) = AdminHtmlAction { implicit request =>
    searchClient.resetUserConfig(userId)
    Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.showUserConfig(userId))
  }

  def getExperiments = AdminHtmlAction { implicit request =>
    heimdal.updateMetrics()
    val experiments = db.readOnly { implicit s => searchConfigExperimentRepo.getNotInactive() }
    val ids = experiments.map(_.id.get)
    val defaultConfigFuture = searchClient.getSearchDefaultConfig
    val kifiVsGoogleFuture = kifiVsGoogle(ids)
    val searchesWithKifiResultsFuture = searchesWithKifiResults(ids)
    Async(for {
      defaultConfig <- defaultConfigFuture
      kifiVsGoogle <- kifiVsGoogleFuture
      searchesWithKifiResults <- searchesWithKifiResultsFuture
    } yield {
      Ok(html.admin.searchConfigExperiments(experiments, defaultConfig.params, kifiVsGoogle.mapValues(Json.stringify(_)), searchesWithKifiResults.mapValues(Json.stringify(_))))
    })
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
      val toSave = exp.copy(description = desc, weight = weight, config = exp.config(params)).withState(state.getOrElse(exp.state))
      db.readWrite { implicit s => searchConfigExperimentRepo.save(toSave) }
    }
    Ok(JsObject(Seq()))
  }

  private def kifiVsGoogle(experiments: Seq[Id[SearchConfigExperiment]]): Future[Map[Option[Id[SearchConfigExperiment]], JsArray]] = {
    val totalResultsClickedFuture = heimdal.getMetricData("total_results_clicked_with_kifi_result_by_search_experiment")
    val kifiResultsClickedFuture = heimdal.getMetricData("kifi_results_clicked_by_search_experiment")

    for {
      totalResultsClicked <- totalResultsClickedFuture
      kifiResultsClicked <- kifiResultsClickedFuture
    } yield {

      val totalResultsClickedByExperiment = ungroup(totalResultsClicked)
      val kifiResultsClickedByExperiment = ungroup(kifiResultsClicked)

      (None +: experiments.map(Some(_))).map { id =>
        id -> Json.arr(
          MetricAuxInfo.augmentMetricData(
            totalResultsClickedByExperiment.getOrElse(id, Json.obj()),
            MetricAuxInfo("nothing yet", Map("null" -> "All Clicks on Searches with Kifi Results"))
          ),
          MetricAuxInfo.augmentMetricData(
            kifiResultsClickedByExperiment.getOrElse(id, Json.obj()),
            MetricAuxInfo("nothing yet", Map("null" -> "KiFi Clicks"))
          )
        )
      }.toMap
    }
  }

  private def searchesWithKifiResults(experiments: Seq[Id[SearchConfigExperiment]]): Future[Map[Option[Id[SearchConfigExperiment]], JsArray]] = {
    val totalSearchesFuture = heimdal.getMetricData("total_unique_searches_by_search_experiment")
    val searchesWithKifiResultsFuture = heimdal.getMetricData("unique_searches_with_kifi_result_by_search_experiment")

    for {
      totalSearches <- totalSearchesFuture
      searchesWithKifiResults <- searchesWithKifiResultsFuture
    } yield {

      val totalSearchesByExperiment = ungroup(totalSearches)
      val searchesWithKifiResultsByExperiment = ungroup(searchesWithKifiResults)

      (None +: experiments.map(Some(_))).map { id =>
        id -> Json.arr(
          MetricAuxInfo.augmentMetricData(
            totalSearchesByExperiment.getOrElse(id, Json.obj()),
            MetricAuxInfo("nothing yet", Map("null" -> "All Searches"))
          ),
          MetricAuxInfo.augmentMetricData(
            searchesWithKifiResultsByExperiment.getOrElse(id, Json.obj()),
            MetricAuxInfo("nothing yet", Map("null" -> "All Searches with Kifi Results"))
          )
        )
      }.toMap
    }
  }

  private def ungroup(searchExperimentMetricData: JsObject): Map[Option[Id[SearchConfigExperiment]], JsObject] = {
    val dataPointsByExperiment = MutableMap[Option[Id[SearchConfigExperiment]], ListBuffer[JsValue]]()
    val header = (searchExperimentMetricData \ "header").as[String]
    val dataByTime = (searchExperimentMetricData \ "data").as[JsArray]
    dataByTime.value.foreach { dataWithTime =>
      val time = (dataWithTime \ "time")
      val dataByExperiment = (dataWithTime \ "data").as[JsArray]
      dataByExperiment.value.foreach { dataWithExperiment =>
        val id = (dataWithExperiment \ "_id") match {
          case js: JsNumber => Some(Id[SearchConfigExperiment](js.value.toLong))
          case jsNull => None
        }
        val count = (dataWithExperiment \ "count")
        dataPointsByExperiment.getOrElseUpdate(id, new ListBuffer[JsValue]()).append(Json.obj("time" -> time, "data" -> Json.arr(Json.obj("count" -> count))))
      }
    }
    dataPointsByExperiment.map { case (id, points) =>
      id -> Json.obj(
        "header" -> JsString(header + s": ${id.map(_.toString).getOrElse("None")}"),
        "data" -> JsArray(points)
      )
    }.toMap
  }
}
