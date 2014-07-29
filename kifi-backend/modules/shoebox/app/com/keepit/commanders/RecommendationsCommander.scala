package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURIRepo }
import com.keepit.curator.CuratorServiceClient
import com.keepit.common.db.slick.Database

import com.google.inject.Inject

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class RecommendationsCommander @Inject() (curator: CuratorServiceClient, db: Database, nUriRepo: NormalizedURIRepo) {

  def adHocRecos(userId: Id[User], howManyMax: Int): Future[Seq[KeepInfo]] = {
    curator.adHocRecos(userId, howManyMax).map { recos =>
      db.readOnlyReplica { implicit session =>
        recos.map { reco =>
          val nUri = nUriRepo.get(reco.uriId)
          KeepInfo(
            title = nUri.title,
            url = nUri.url,
            isPrivate = false
          )
        }
      }
    }
  }
}
