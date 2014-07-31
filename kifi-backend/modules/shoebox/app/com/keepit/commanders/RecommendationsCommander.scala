package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model.ScoreType._
import com.keepit.model.{ User, NormalizedURIRepo }
import com.keepit.curator.CuratorServiceClient
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.Recommendation

import com.google.inject.Inject

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class RecommendationsCommander @Inject() (
    curator: CuratorServiceClient,
    db: Database,
    nUriRepo: NormalizedURIRepo,
    uriSummaryCommander: URISummaryCommander) {

  def adHocRecos(userId: Id[User], howManyMax: Int, scoreCoefficientsUpdate: Map[ScoreType, Float]): Future[Seq[KeepInfo]] = {
    curator.adHocRecos(userId, howManyMax, scoreCoefficientsUpdate).flatMap { recos =>
      db.readOnlyReplica { implicit session =>
        Future.sequence(recos.map { reco =>
          val nUri = nUriRepo.get(reco.uriId)
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
}
