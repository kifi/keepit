package com.keepit.search.controllers.website

import com.google.inject.Inject
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.heimdal._
import com.keepit.search._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType
import com.keepit.search.tracking.{ SearchEventCommander, KifiHitContext, BasicSearchContext }
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.akka.SafeFuture

class WebsiteSearchEventController @Inject() (
  val userActionsHelper: UserActionsHelper,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  searchEventCommander: SearchEventCommander)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends UserActions with SearchServiceController with Logging {

  def clickedKifiResult = UserAction(parse.tolerantJson) { request =>
    val clickedAt = currentDateTime
    val userId = request.userId
    val json = request.body
    val basicSearchContext = json.as[BasicSearchContext]
    val resultPosition = (json \ "resultPosition").as[Int]
    val searchResultUrl = (json \ "resultUrl").as[String]
    val isDemo = request.experiments.contains(ExperimentType.DEMO)
    val query = basicSearchContext.query
    val kifiHitContext = (json \ "hit").as[KifiHitContext]

    SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      searchEventCommander.clickedKifiResult(userId, basicSearchContext, query, searchResultUrl, resultPosition, isDemo, clickedAt, kifiHitContext)(contextBuilder.build)
    }
    Ok
  }

  def searched = UserAction(parse.tolerantJson) { request =>
    val time = clock.now()
    val userId = request.userId
    val json = request.body
    val basicSearchContext = json.as[BasicSearchContext]
    val endedWith = (json \ "endedWith").as[String] //either "unload" or "refinement"
    SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      searchEventCommander.searched(userId, time, basicSearchContext, endedWith)(contextBuilder.build)
    }
    Ok
  }
}
