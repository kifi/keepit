package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ SearchServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.heimdal._
import com.keepit.search._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import com.keepit.common.akka.SafeFuture

class ExtSearchEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  searchEventCommander: SearchEventCommander)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  def clickedSearchResult = JsonAction.authenticatedParseJson { request =>
    val clickedAt = currentDateTime
    val userId = request.userId
    val json = request.body
    val basicSearchContext = try {
      json.as[BasicSearchContext]
    } catch {
      case t: Throwable => throw new Exception(s"can't parse BasicSearchContext or user $userId json: ${json.toString()}", t)
    }
    val resultSource = (json \ "resultSource").as[String]
    val resultPosition = (json \ "resultPosition").as[Int]
    val searchResultUrl = (json \ "resultUrl").as[String]
    val isDemo = request.experiments.contains(ExperimentType.DEMO)
    val query = basicSearchContext.query

    SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      SearchEngine.get(resultSource) match {
        case SearchEngine.Kifi => {
          val kifiHitContext = (json \ "hit").as[KifiHitContext]
          searchEventCommander.clickedKifiResult(userId, basicSearchContext, query, searchResultUrl, resultPosition, isDemo, clickedAt, kifiHitContext)(contextBuilder.build)
        }
        case theOtherGuys =>
          searchEventCommander.clickedOtherResult(userId, basicSearchContext, query, searchResultUrl, resultPosition, clickedAt, theOtherGuys)(contextBuilder.build)
      }
    }
    Ok
  }

  def searched = JsonAction.authenticatedParseJson { request =>
    val time = clock.now()
    val userId = request.userId
    val json = request.body
    val basicSearchContext = try {
      json.as[BasicSearchContext]
    } catch {
      case t: Throwable => throw new Exception(s"can't parse BasicSearchContext json: ${json.toString()}", t)
    }
    val endedWith = (json \ "endedWith").as[String]
    SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      searchEventCommander.searched(userId, time, basicSearchContext, endedWith)(contextBuilder.build)
    }
    Ok
  }

  def updateBrowsingHistory() = JsonAction.authenticatedParseJson { request =>
    // decommissioned
    Ok
  }
}

