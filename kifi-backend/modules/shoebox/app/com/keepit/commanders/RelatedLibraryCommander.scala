package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.service.RequestConsolidator
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.LibraryQualityHelper
import com.keepit.model._
import com.kifi.macros.json
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import scala.collection.mutable
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import scala.concurrent.Future

@ImplementedBy(classOf[RelatedLibraryCommanderImpl])
trait RelatedLibraryCommander {
  def suggestedLibrariesInfo(libId: Id[Library], userIdOpt: Option[Id[User]]): Future[(Seq[FullLibraryInfo], Seq[RelatedLibraryKind])]
  def suggestedLibraries(libId: Id[Library]): Future[(Seq[RelatedLibrary])]
  def topicRelatedLibraries(libId: Id[Library]): Future[Seq[RelatedLibrary]]
  def topFollowedLibraries(minFollow: Int): Future[Seq[RelatedLibrary]]
}

@Singleton
class RelatedLibraryCommanderImpl @Inject() (
    db: Database,
    libRepo: LibraryRepo,
    libMemRepo: LibraryMembershipRepo,
    libCommander: LibraryCommander,
    cortex: CortexServiceClient,
    userCommander: UserCommander,
    libQualityHelper: LibraryQualityHelper,
    relatedLibsCache: RelatedLibrariesCache,
    topLibsCache: TopFollowedLibrariesCache,
    airbrake: AirbrakeNotifier) extends RelatedLibraryCommander with Logging {

  protected val DEFAULT_MIN_FOLLOW = 5
  private val RETURN_SIZE = 5
  private val SUB_RETURN_SIZE = 20 // cap return from component
  private val SPECIAL_OWNER_RETURN_SIZE = 1

  private val SPECIAL_OWNERS = Set(Id[User](10015)) // e.g. Kifi Editorial

  // main method
  def suggestedLibrariesInfo(libId: Id[Library], userIdOpt: Option[Id[User]]): Future[(Seq[FullLibraryInfo], Seq[RelatedLibraryKind])] = {
    val t0 = System.currentTimeMillis
    val suggestedLibsFut = suggestedLibraries(libId)

    val fakeUsers = userCommander.getAllFakeUsers()
    val userLibs: Set[Id[Library]] = userIdOpt match {
      case Some(userId) => db.readOnlyReplica { implicit s => libMemRepo.getWithUserId(userId) }.map { _.libraryId }.toSet
      case None => Set()
    }

    def isUnwantedLibrary(relatedLib: RelatedLibrary): Boolean = {
      val lib = relatedLib.library

      lib.state == LibraryStates.INACTIVE ||
        fakeUsers.contains(lib.ownerId) ||
        userLibs.contains(lib.id.get) ||
        libQualityHelper.isBadLibraryName(lib.name)

    }

    suggestedLibsFut
      .map { libs => libs.filter { !isUnwantedLibrary(_) }.take(RETURN_SIZE) }
      .flatMap { relatedLibs =>
        val t1 = System.currentTimeMillis
        val (libs, kinds) = relatedLibs.map { x => (x.library, x.kind) }.unzip
        val fullInfosFut = libCommander.createFullLibraryInfos(userIdOpt, true, 10, 0, ProcessedImageSize.Large.idealSize, libs, ProcessedImageSize.Large.idealSize, withKeepTime = true).map { _.map { _._2 } }
        fullInfosFut.map { info =>
          val t2 = System.currentTimeMillis
          statsd.timing("commander.RelatedLibraryCommander.getSuggestedLibs", t1 - t0, 1.0)
          statsd.timing("commander.RelatedLibraryCommander.getFullLibInfo", t2 - t1, 1.0)
          if (info.size == kinds.size) (info, kinds)
          else {
            airbrake.notify(s"error in getting suggested libraries for lib ${libId}, user: ${userIdOpt}. info array and kinds array do not match in size.")
            (Seq(), Seq())
          }
        }
      }

  }

  def suggestedLibraries(libId: Id[Library]): Future[Seq[RelatedLibrary]] = {
    val relatedLibs = relatedLibsCache.getOrElseFuture(RelatedLibariesKey(libId)) {
      val topicRelatedF = topicRelatedLibraries(libId)
      val ownerLibsF = librariesFromSameOwner(libId)
      val popularF = topFollowedLibraries()
      for {
        topicRelated <- topicRelatedF
        ownerLibs <- ownerLibsF
        popular <- popularF
      } yield {
        val libs = topicRelated ++
          ownerLibs.sortBy(-_.library.memberCount).take(SUB_RETURN_SIZE) ++
          util.Random.shuffle(popular.filter(_.library.id.get != libId)).take(SUB_RETURN_SIZE)

        // dedup
        val libIds = mutable.Set.empty[Id[Library]]
        val libs2 = libs.flatMap { lib =>
          if (libIds.contains(lib.library.id.get)) None
          else {
            libIds += lib.library.id.get
            Some(lib)
          }
        }
        RelatedLibraries(libs2)
      }
    }

    relatedLibs.map(_.libs)
  }

  def topicRelatedLibraries(libId: Id[Library]): Future[Seq[RelatedLibrary]] = {
    cortex.similarLibraries(libId, limit = SUB_RETURN_SIZE).map { ids =>
      db.readOnlyMaster { implicit s =>
        ids.map { id => libRepo.get(id) }
      }.filter(_.visibility == LibraryVisibility.PUBLISHED)
        .map { lib => RelatedLibrary(lib, RelatedLibraryKind.TOPIC) }
    }
  }

  def librariesFromSameOwner(libId: Id[Library], minFollow: Int = DEFAULT_MIN_FOLLOW): Future[Seq[RelatedLibrary]] = {
    db.readOnlyReplicaAsync { implicit s =>
      val owner = libRepo.get(libId).ownerId
      val libs = libRepo.getAllByOwner(owner)
        .filter { x => x.id.get != libId && x.visibility == LibraryVisibility.PUBLISHED && x.memberCount >= (minFollow + 1) }
        .map { lib => RelatedLibrary(lib, RelatedLibraryKind.OWNER) }
      if (SPECIAL_OWNERS.contains(owner)) {
        libs.take(SPECIAL_OWNER_RETURN_SIZE)
      } else libs
    }
  }

  def topFollowedLibraries(minFollow: Int = DEFAULT_MIN_FOLLOW): Future[Seq[RelatedLibrary]] = {
    val topK = 100
    topLibsCache.getOrElseFuture(new TopFollowedLibrariesKey()) {
      db.readOnlyReplicaAsync { implicit s =>
        val libs = libRepo.filterPublishedByMemberCount(minFollow + 1, limit = topK).map { lib => RelatedLibrary(lib, RelatedLibraryKind.POPULAR) }
        TopFollowedLibraries(libs)
      }
    }.map { _.libs }
  }

}

@json case class RelatedLibraryKind(value: String)

object RelatedLibraryKind {
  val TOPIC = RelatedLibraryKind("topic")
  val OWNER = RelatedLibraryKind("owner")
  val POPULAR = RelatedLibraryKind("popular")
}

@json case class RelatedLibrary(library: Library, kind: RelatedLibraryKind)

@json case class RelatedLibraries(libs: Seq[RelatedLibrary])

case class RelatedLibariesKey(id: Id[Library]) extends Key[RelatedLibraries] {
  override val version = 2
  val namespace = "related_libraries_by_id"
  def toKey(): String = id.id.toString
}

class RelatedLibrariesCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RelatedLibariesKey, RelatedLibraries](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

@json case class TopFollowedLibraries(libs: Seq[RelatedLibrary])

case class TopFollowedLibrariesKey() extends Key[TopFollowedLibraries] {
  override val version = 1
  val namespace = "top_followed_libraries_key"
  def toKey(): String = version.toString
}

class TopFollowedLibrariesCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[TopFollowedLibrariesKey, TopFollowedLibraries](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
