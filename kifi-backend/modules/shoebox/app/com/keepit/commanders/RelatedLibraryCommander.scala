package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.service.RequestConsolidator
import com.keepit.cortex.CortexServiceClient
import com.keepit.model.{ LibraryRepo, Library }
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._

import scala.concurrent.Future

@ImplementedBy(classOf[RelatedLibraryCommanderImpl])
trait RelatedLibraryCommander {
  def suggestedLibraries(libId: Id[Library]): Future[Seq[Library]]
  def relatedLibraries(libId: Id[Library]): Future[Seq[Library]]
  def topFollowedLibraries(minFollow: Int = 5, topK: Int = 5): Future[Seq[Library]]
}

@Singleton
class RelatedLibraryCommanderImpl @Inject() (
    db: Database,
    libRepo: LibraryRepo,
    cortex: CortexServiceClient) extends RelatedLibraryCommander {

  private val consolidater = new RequestConsolidator[Id[Library], Seq[Library]](FiniteDuration(10, MINUTES))

  def suggestedLibraries(libId: Id[Library]): Future[Seq[Library]] = consolidater(libId) { libId =>
    for {
      related <- relatedLibraries(libId)
      popular <- topFollowedLibraries()
    } yield {
      if (related.isEmpty) {
        util.Random.shuffle(popular.filter(_.id.get != libId)).take(5)
      } else related
    }
  }

  def relatedLibraries(libId: Id[Library]): Future[Seq[Library]] = {
    cortex.similarLibraries(libId, limit = 5).map { ids =>
      db.readOnlyReplica { implicit s =>
        ids.map { id => libRepo.get(id) }
      }
    }
  }

  def topFollowedLibraries(minFollow: Int = 5, topK: Int = 100): Future[Seq[Library]] = {
    db.readOnlyReplicaAsync { implicit s =>
      libRepo.filterPublishedByMemberCount(minFollow + 1, limit = topK)
    }
  }

}
