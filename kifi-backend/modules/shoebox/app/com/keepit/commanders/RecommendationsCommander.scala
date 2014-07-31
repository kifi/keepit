package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURIRepo, NormalizedURI }
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

  def adHocRecos(userId: Id[User], howManyMax: Int): Future[Seq[KeepInfo]] = {
    curator.adHocRecos(userId, howManyMax).flatMap { recos =>
      val recosWithUris: Seq[(Recommendation, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }.filterNot(_._2.restriction.isDefined) //simple (overly strict) porn filter stop gap until proper curator integration is done

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
}
