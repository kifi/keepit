package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ UserRequest, UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.net.UserAgent
import com.keepit.curator.model.{ RecommendationSubSource, RecommendationSource }
import com.keepit.model.{ ExperimentType, NormalizedURI, UriRecommendationFeedback }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future

class MobileRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database) extends UserActions with ShoeboxServiceController {

  def topRecosV1(more: Boolean, recencyWeight: Float) = UserAction.async { request =>
    val uriRecosF = commander.topRecos(request.userId, getRecommendationSource(request), RecommendationSubSource.RecommendationsFeed, more, recencyWeight)
    uriRecosF map { recos => Ok(Json.toJson(recos)) }
  }

  def topRecosV2(more: Boolean, recencyWeight: Float) = UserAction.async { request =>
    val uriRecosF = commander.topRecos(request.userId, getRecommendationSource(request), RecommendationSubSource.RecommendationsFeed, more, recencyWeight)

    val sendLibRecos = userExperimentCommander.getExperimentsByUser(request.userId).exists(ExperimentType.CURATOR_LIBRARY_RECOS ==)
    val libRecosF =
      if (sendLibRecos) commander.topPublicLibraryRecos(request.userId, 5, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed)
      else Future.successful(Seq.empty)

    for (libs <- libRecosF; uris <- uriRecosF) yield Ok {
      Json.toJson(util.Random.shuffle(uris ++ libs))
    }
  }

  def topPublicRecos() = UserAction.async { request =>
    commander.topPublicRecos(request.userId).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def trash(id: ExternalId[NormalizedURI]) = UserAction.async { request =>
    val feedback = UriRecommendationFeedback(trashed = Some(true), source = Some(getRecommendationSource(request)),
      subSource = Some(RecommendationSubSource.RecommendationsFeed))
    commander.updateUriRecommendationFeedback(request.userId, id, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  private def getRecommendationSource(request: UserRequest[_]): RecommendationSource = {
    val agent = UserAgent(request)
    if (agent.isKifiAndroidApp) RecommendationSource.Android
    else if (agent.isKifiIphoneApp) RecommendationSource.IOS
    else throw new IllegalArgumentException(s"the user agent is not of a kifi application: $agent")
  }

}
