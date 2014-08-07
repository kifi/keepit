package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.RecommendationInfo
import com.keepit.common.db.slick.Database

import com.google.inject.Inject

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class RecommendationsCommander @Inject() (
    curator: CuratorServiceClient,
    db: Database,
    nUriRepo: NormalizedURIRepo,
    uriSummaryCommander: URISummaryCommander) {

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

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    curator.updateUriRecommendationFeedback(userId, uriId, feedback)
  }

  def UriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI], interaction: UriRecommendationUserInteraction): Future[Boolean] = {
    curator.updateUriRecommendationUserInteraction(userId, uriId, interaction)
  }

}
