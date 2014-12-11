package com.keepit.search.controllers.ext

import com.keepit.common.http._
import com.google.inject.Inject
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
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
  val userActionsHelper: UserActionsHelper,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  searchEventCommander: SearchEventCommander)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends UserActions with SearchServiceController with Logging {

  def clickedSearchResult = UserAction(parse.tolerantJson) { request =>
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
      if (basicSearchContext.guided) {
        contextBuilder += ("guided", true)
      }
      SearchEngine.get(resultSource) match {
        case SearchEngine.Kifi =>
          val kifiHitContext = try {
            (json \ "hit").as[KifiHitContext]
          } catch {
            case e: Exception =>
              throw new Exception(s"""Can't parse json "$json" by user agent ${request.userAgentOpt} or user ${request.userId}""")
          }
          searchEventCommander.clickedKifiResult(userId, basicSearchContext, query, searchResultUrl, resultPosition, isDemo, clickedAt, kifiHitContext)(contextBuilder.build)
        case theOtherGuys =>
          searchEventCommander.clickedOtherResult(userId, basicSearchContext, query, searchResultUrl, resultPosition, clickedAt, theOtherGuys)(contextBuilder.build)
      }
    }
    Ok
  }

  def searched = UserAction(parse.tolerantJson) { request =>
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
      if (basicSearchContext.guided) {
        contextBuilder += ("guided", true)
      }
      searchEventCommander.searched(userId, time, basicSearchContext, endedWith)(contextBuilder.build)
    }
    Ok
  }

}

