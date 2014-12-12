package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.service.RequestConsolidator
import com.keepit.cortex.CortexServiceClient
import com.keepit.model.{ User, LibraryMembershipRepo, LibraryRepo, Library }
import com.keepit.social.BasicUser
import com.kifi.macros.json
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._

import scala.concurrent.Future

@ImplementedBy(classOf[RelatedLibraryCommanderImpl])
trait RelatedLibraryCommander {
  def suggestedLibrariesInfo(libId: Id[Library], userIdOpt: Option[Id[User]]): Future[(Seq[FullLibraryInfo], Boolean)] // boolean flag: true if related libraries
  def suggestedLibraries(libId: Id[Library]): Future[(Seq[Library], Boolean)]
  def relatedLibraries(libId: Id[Library]): Future[Seq[Library]]
  def topFollowedLibraries(minFollow: Int = 5, topK: Int = 5): Future[Seq[Library]]
}

@Singleton
class RelatedLibraryCommanderImpl @Inject() (
    db: Database,
    libRepo: LibraryRepo,
    libMemRepo: LibraryMembershipRepo,
    libCommander: LibraryCommander,
    cortex: CortexServiceClient) extends RelatedLibraryCommander {

  private val consolidater = new RequestConsolidator[Id[Library], (Seq[Library], Boolean)](FiniteDuration(10, MINUTES))

  def suggestedLibrariesInfo(libId: Id[Library], userIdOpt: Option[Id[User]]): Future[(Seq[FullLibraryInfo], Boolean)] = {
    val suggestedLibsFut = suggestedLibraries(libId)
    val userLibs: Set[Id[Library]] = userIdOpt match {
      case Some(userId) => db.readOnlyReplica { implicit s => libMemRepo.getWithUserId(userId) }.map { _.libraryId }.toSet
      case None => Set()
    }
    val infoFut = suggestedLibsFut
      .map { case (libs, _) => libs }
      .map { libs => libs.filter { x => !userLibs.contains(x.id.get) } }
      .flatMap { libs => libCommander.createFullLibraryInfos(userIdOpt, true, 10, 0, ProcessedImageSize.Large.idealSize, libs) }

    for {
      info <- infoFut
      suggestedLibs <- suggestedLibsFut
    } yield (info, suggestedLibs._2)
  }

  def suggestedLibraries(libId: Id[Library]): Future[(Seq[Library], Boolean)] = consolidater(libId) { libId =>
    for {
      related <- relatedLibraries(libId)
      popular <- topFollowedLibraries()
    } yield {
      if (related.isEmpty) {
        (util.Random.shuffle(popular.filter(_.id.get != libId)).take(5), false)
      } else (related, true)
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

@json
case class RelatedLibraryInfo(
  id: PublicId[Library],
  name: String,
  url: String,
  owner: BasicUser,
  followers: Seq[BasicUser],
  numKeeps: Int,
  numFollowers: Int)

object RelatedLibraryInfo {
  def fromFullLibraryInfo(info: FullLibraryInfo, isAuthenticatedRequest: Boolean): RelatedLibraryInfo = {
    if (isAuthenticatedRequest) {
      RelatedLibraryInfo(info.id, info.name, info.url, info.owner, info.followers, info.numKeeps, info.numFollowers)
    } else {
      RelatedLibraryInfo(info.id, info.name, info.url, info.owner, Seq(), info.numKeeps, info.numFollowers)
    }
  }
}
