package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.RecommendationInfo
import com.keepit.common.db.slick.Database

import com.google.inject.Inject
import com.keepit.normalizer.NormalizedURIInterner

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsNull, Json }

import scala.concurrent.Future

class RecommendationsCommander @Inject() (
    curator: CuratorServiceClient,
    db: Database,
    nUriRepo: NormalizedURIRepo,
    uriSummaryCommander: URISummaryCommander,
    normalizedURIInterner: NormalizedURIInterner) {

  def adHocRecos(userId: Id[User], howManyMax: Int, scoreCoefficientsUpdate: UriRecommendationScores): Future[Seq[KeepInfo]] = {
    curator.adHocRecos(userId, howManyMax, scoreCoefficientsUpdate).flatMap { recos =>
      val recosWithUris: Seq[(RecommendationInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }.filter(_._2.state == NormalizedURIStates.SCRAPED)

      Future.sequence(recosWithUris.map {
        case (reco, nUri) =>
          uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
            KeepInfo(
              title = nUri.title,
              url = nUri.url,
              isPrivate = false,
              uriSummary = Some(uriSummary.copy(description = reco.explain))
            )
          }
      })

    }
  }

  def updateUriRecommendationFeedback(userId: Id[User], url: String, feedback: UriRecommendationFeedback): Future[Boolean] = {
    val uriOpt = db.readOnlyMaster { implicit s =>
      normalizedURIInterner.getByUri(url) //using cache
    }
    uriOpt match {
      case Some(uri) => curator.updateUriRecommendationFeedback(userId, uri.id.get, feedback)
      case None => Future.successful(false)
    }
  }

  def updateUriRecommendationUserInteraction(userId: Id[User], url: String, vote: Option[Boolean]): Future[Boolean] = {
    val interaction = UriRecommendationUserInteraction(vote = vote)
    val uriOpt = db.readOnlyMaster { implicit s =>
      normalizedURIInterner.getByUri(url) //using cache
    }
    uriOpt match {
      case Some(uri) => curator.updateUriRecommendationUserInteraction(userId, uri.id.get, interaction)
      case None => Future.successful(false)
    }
  }

}
