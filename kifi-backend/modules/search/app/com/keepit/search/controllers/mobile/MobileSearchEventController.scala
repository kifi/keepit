package com.keepit.search.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.heimdal.{ KifiHitContext, SearchEngine, BasicSearchContext, HeimdalContextBuilderFactory }
import com.keepit.search.SearchEventCommander
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.ExperimentType
import com.keepit.common.akka.SafeFuture
import play.api.libs.json.JsArray
import play.api.libs.concurrent.Execution.Implicits._

class MobileSearchEventController @Inject() (
  val userActionsHelper: UserActionsHelper,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  searchEventCommander: SearchEventCommander)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends UserActions with SearchServiceController with Logging {

  def clickedSearchResult = UserAction(parse.tolerantJson) { request =>
    val clickedAt = clock.now()
    val userId = request.userId
    val json = request.body
    val basicSearchContext = json.as[BasicSearchContext]
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

  def searched = UserAction(parse.tolerantJson) { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val basicSearchContext = json.as[BasicSearchContext]
    val endedWith = (json \ "endedWith").as[String]
    SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      searchEventCommander.searched(userId, time, basicSearchContext, endedWith)(contextBuilder.build)
    }
    Ok
  }
}

