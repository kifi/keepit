package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.service.RequestConsolidator
import com.keepit.cortex.CortexServiceClient
import com.keepit.model.{ LibraryImageInfo, HexColor, User, LibraryMembershipRepo, LibraryRepo, Library, LibraryVisibility }
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
      .map { libs => libs.filter { x => !userLibs.contains(x.id.get) }.take(5) }
      .flatMap { libs => libCommander.createFullLibraryInfos(userIdOpt, true, 10, 0, ProcessedImageSize.Large.idealSize, libs, ProcessedImageSize.Large.idealSize) }

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
        (util.Random.shuffle(popular.filter(_.id.get != libId)), false)
      } else (related, true)
    }
  }

  def relatedLibraries(libId: Id[Library]): Future[Seq[Library]] = {
    cortex.similarLibraries(libId, limit = 20).map { ids =>
      db.readOnlyReplica { implicit s =>
        ids.map { id => libRepo.get(id) }
      }.filter(_.visibility == LibraryVisibility.PUBLISHED)
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
  numFollowers: Int,
  color: Option[HexColor],
  image: Option[LibraryImageInfo])

object RelatedLibraryInfo {
  def fromFullLibraryInfo(info: FullLibraryInfo, isAuthenticatedRequest: Boolean): RelatedLibraryInfo = {
    val showableFollowers = if (isAuthenticatedRequest) {
      val goodLooking = info.followers.filter(_.pictureName != "0.jpg")
      if (goodLooking.size < 8) goodLooking else goodLooking.take(3) // cannot show more than 8 avatars in frontend
    } else Seq.empty
    RelatedLibraryInfo(info.id, info.name, info.url, info.owner, showableFollowers, info.numKeeps,
      info.numFollowers, info.color, info.image)
  }
}
