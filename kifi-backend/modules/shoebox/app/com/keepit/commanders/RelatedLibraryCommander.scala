package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.service.RequestConsolidator
import com.keepit.cortex.CortexServiceClient
import com.keepit.model.{ User, LibraryMembershipRepo, LibraryRepo, Library, LibraryVisibility }
import com.keepit.social.BasicUser
import com.kifi.macros.json
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._

import scala.concurrent.Future

@ImplementedBy(classOf[RelatedLibraryCommanderImpl])
trait RelatedLibraryCommander {
  def suggestedLibrariesInfo(libId: Id[Library], userIdOpt: Option[Id[User]]): Future[(Seq[FullLibraryInfo], Seq[RelatedLibraryKind])]
  def suggestedLibraries(libId: Id[Library]): Future[(Seq[RelatedLibrary])]
  def topicRelatedLibraries(libId: Id[Library]): Future[Seq[RelatedLibrary]]
  def topFollowedLibraries(minFollow: Int, topK: Int): Future[Seq[RelatedLibrary]]
}

@Singleton
class RelatedLibraryCommanderImpl @Inject() (
    db: Database,
    libRepo: LibraryRepo,
    libMemRepo: LibraryMembershipRepo,
    libCommander: LibraryCommander,
    cortex: CortexServiceClient) extends RelatedLibraryCommander {

  private val DEFAULT_MIN_FOLLOW = 5
  private val RETURN_SIZE = 5

  private val consolidater = new RequestConsolidator[Id[Library], Seq[RelatedLibrary]](FiniteDuration(10, MINUTES))

  // main method
  def suggestedLibrariesInfo(libId: Id[Library], userIdOpt: Option[Id[User]]): Future[(Seq[FullLibraryInfo], Seq[RelatedLibraryKind])] = {
    val suggestedLibsFut = suggestedLibraries(libId)
    val userLibs: Set[Id[Library]] = userIdOpt match {
      case Some(userId) => db.readOnlyReplica { implicit s => libMemRepo.getWithUserId(userId) }.map { _.libraryId }.toSet
      case None => Set()
    }
    suggestedLibsFut
      .map { libs => libs.filter { case RelatedLibrary(lib, kind) => !userLibs.contains(lib.id.get) }.take(RETURN_SIZE) }
      .flatMap { relatedLibs =>
        val libs = relatedLibs.map { _.library }
        val kinds = relatedLibs.map { _.kind }
        val fullInfosFut = libCommander.createFullLibraryInfos(userIdOpt, true, 10, 0, ProcessedImageSize.Large.idealSize, libs, ProcessedImageSize.Large.idealSize)
        fullInfosFut.map { info =>
          assert(info.size == kinds.size)
          (info, kinds)
        }
      }

  }

  def suggestedLibraries(libId: Id[Library]): Future[Seq[RelatedLibrary]] = consolidater(libId) { libId =>
    for {
      topicRelated <- topicRelatedLibraries(libId)
      ownerLibs <- librariesFromSameOwner(libId)
      popular <- topFollowedLibraries()
    } yield {
      topicRelated ++ ownerLibs.sortBy(-_.library.memberCount) ++ util.Random.shuffle(popular.filter(_.library.id.get != libId))
    }
  }

  def topicRelatedLibraries(libId: Id[Library]): Future[Seq[RelatedLibrary]] = {
    cortex.similarLibraries(libId, limit = 20).map { ids =>
      db.readOnlyReplica { implicit s =>
        ids.map { id => libRepo.get(id) }
      }.filter(_.visibility == LibraryVisibility.PUBLISHED)
        .map { lib => RelatedLibrary(lib, RelatedLibraryKind.TOPIC) }
    }
  }

  def librariesFromSameOwner(libId: Id[Library], minFollow: Int = DEFAULT_MIN_FOLLOW): Future[Seq[RelatedLibrary]] = {
    db.readOnlyReplicaAsync { implicit s =>
      val owner = libRepo.get(libId).ownerId
      libRepo.getAllByOwner(owner)
        .filter { x => x.id.get != libId && x.visibility == LibraryVisibility.PUBLISHED && x.memberCount >= (minFollow + 1) }
        .map { lib => RelatedLibrary(lib, RelatedLibraryKind.OWNER) }
    }
  }

  def topFollowedLibraries(minFollow: Int = DEFAULT_MIN_FOLLOW, topK: Int = 100): Future[Seq[RelatedLibrary]] = {
    db.readOnlyReplicaAsync { implicit s =>
      libRepo.filterPublishedByMemberCount(minFollow + 1, limit = topK).map { lib => RelatedLibrary(lib, RelatedLibraryKind.POPULAR) }
    }
  }

}

@json case class RelatedLibraryKind(value: String)

object RelatedLibraryKind {
  val TOPIC = RelatedLibraryKind("topic")
  val OWNER = RelatedLibraryKind("owner")
  val POPULAR = RelatedLibraryKind("popular")
}

case class RelatedLibrary(library: Library, kind: RelatedLibraryKind)

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
      val showableFollowers = {
        val goodLooking = info.followers.filter(_.pictureName != "0.jpg")
        if (goodLooking.size < 8) goodLooking else goodLooking.take(3) // cannot show more than 8 avatars in frontend
      }
      RelatedLibraryInfo(info.id, info.name, info.url, info.owner, showableFollowers, info.numKeeps, info.numFollowers)
    } else {
      RelatedLibraryInfo(info.id, info.name, info.url, info.owner, Seq(), info.numKeeps, info.numFollowers)
    }
  }
}
