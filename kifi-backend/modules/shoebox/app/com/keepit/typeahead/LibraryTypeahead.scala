package com.keepit.typeahead

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Inject, Provider, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.cache._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.store.S3Bucket
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.typeahead.LibraryTypeahead.UserLibraryTypeahead
import com.kifi.macros.json

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object LibraryTypeahead {
  type UserLibraryTypeahead = PersonalTypeahead[User, Library, LibraryTypeaheadResult]
}

@json case class LibraryTypeaheadResult(id: Id[Library], name: String, importance: Int) {
  // importance is a measure to assist in ranking of libraries not recently used by the user. Sum of (lowest is best):
  //  • -1 active membership
  //  • -1 user is library owner
  //  • -1 visited previously
  //  • -1 has keeps
  //  • -1 keeps in past 3 days
  //  • -1 keeps in past 15 days
  //  • -1 user created
}

@Singleton
class LibraryTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    store: LibraryTypeaheadStore,
    cache: LibraryFilterTypeaheadCache,
    libResCache: LibraryResultTypeaheadCache,
    libraryInfoCommander: Provider[LibraryInfoCommander],
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    libraryRepo: LibraryRepo,
    clock: Clock,
    implicit val ec: ExecutionContext,
    implicit val config: PublicIdConfiguration) extends Typeahead[User, Library, LibraryTypeaheadResult, UserLibraryTypeahead] with Logging {

  protected val refreshRequestConsolidationWindow = 20 seconds

  protected val fetchRequestConsolidationWindow = 30 seconds

  protected def get(userId: Id[User]): Future[Option[UserLibraryTypeahead]] = {
    val filterOpt = cache.getOrElseOpt(LibraryFilterTypeaheadKey(userId)) {
      store.getWithMetadata(userId).map {
        case (filter, meta) =>
          if (meta.exists(m => m.lastModified.plusMinutes(5).isBefore(currentDateTime))) {
            refresh(userId)
          }
          filter
      }
    }
    Future.successful(filterOpt.map(filter => PersonalTypeahead(userId, filter, getInfos(userId))))
  }

  // Useful on library creation, name changes, or when it's deleted
  def refreshForAllCollaborators(libraryId: Id[Library]): Future[Unit] = {
    Future {
      db.readOnlyReplicaAsync { implicit s =>
        val collaborators = libraryMembershipRepo.getCollaboratorsByLibrary(Set(libraryId)).values.headOption.getOrElse(Set.empty).toSeq
        val invited = libraryInviteRepo.getWithLibraryId(libraryId).flatMap(_.userId)
        val lib = libraryRepo.get(libraryId)
        val orgMembers = if (lib.organizationMemberAccess.exists(_ == LibraryAccess.READ_WRITE)) {
          lib.organizationId.map(oid => organizationMembershipRepo.getAllByOrgId(oid).map(_.userId)).getOrElse(Set.empty)
        } else Set.empty[Id[User]]
        (collaborators ++ invited ++ orgMembers).distinct
      }.flatMap { userIds =>
        FutureHelpers.sequentialExec(userIds)(refresh)
      }
    }.flatMap(f => f)
  }

  private def getInfos(userId: Id[User])(ids: Seq[Id[Library]]): Future[Seq[LibraryTypeaheadResult]] = {
    SafeFuture {
      val idSet = ids.toSet
      libResCache(directCacheAccess).bulkGetOrElse(idSet.map(i => LibraryResultTypeaheadKey(userId, i))) { missingKeys =>
        db.readOnlyReplica { implicit session =>
          val missingIds = missingKeys.map(_.libraryId)
          val libs = libraryRepo.getActiveByIds(missingIds)
          val mems = libraryMembershipRepo.getWithLibraryIdsAndUserId(missingIds, userId)
          libs.values.map { lib =>
            LibraryResultTypeaheadKey(userId, lib.id.get) -> LibraryTypeaheadResult(lib.id.get, lib.name, calcImportance(lib, mems.get(lib.id.get)))
          }.toMap
        }
      }.values.toSeq
    }
  }

  def getAllRelevantLibraries(id: Id[User]): Seq[(Id[Library], LibraryTypeaheadResult)] = {
    val startTime = clock.now
    val libs = db.readOnlyReplica { implicit session =>
      libraryInfoCommander.get.getLibrariesUserCanKeepTo(id, includeOrgLibraries = true)
    }.collect {
      case (l, mOpt, _) if l.isActive =>
        l.id.get -> LibraryTypeaheadResult(l.id.get, l.name, calcImportance(l, mOpt))
    }
    log.info(s"[LibraryTypeahead#getAllRelevantLibraries] $id took ${clock.now.getMillis - startTime.getMillis}")
    libs
  }

  private def calcImportance(lib: Library, memOpt: Option[LibraryMembership]): Int = {
    Seq(
      memOpt.isDefined,
      memOpt.exists(lib.ownerId == _.userId),
      memOpt.exists(_.lastViewed.isDefined),
      lib.keepCount > 0,
      lib.lastKept.exists(_.isAfter(clock.now.minusHours(72))),
      lib.lastKept.exists(_.isAfter(clock.now.minusHours(360))),
      lib.kind == LibraryKind.USER_CREATED
    ).map {
        case true => -1
        case false => 0
      }.sum
  }

  protected def extractName(info: LibraryTypeaheadResult): String = info.name

  protected def invalidate(typeahead: UserLibraryTypeahead) = {
    val userId = typeahead.ownerId
    val filter = typeahead.filter
    cache.set(LibraryFilterTypeaheadKey(userId), filter)
    store += (userId -> filter)
  }

  protected def create(userId: Id[User]) = {
    SafeFuture(getAllRelevantLibraries(userId)).map { allInfos =>
      allInfos.foreach { l =>
        libResCache(directCacheAccess).set(LibraryResultTypeaheadKey(userId, l._2.id), l._2)
      }

      val filter = buildFilter(userId, allInfos)

      val allMap = allInfos.toMap
      def getter(libs: Seq[Id[Library]]) = Future.successful {
        libs.flatMap(allMap.get)
      }
      PersonalTypeahead(userId, filter, getter)
    }
  }
}

trait LibraryTypeaheadStore extends PrefixFilterStore[User, Library]

class S3LibraryTypeaheadStore @Inject() (bucket: S3Bucket, amazonS3Client: AmazonS3, accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User, Library](bucket, amazonS3Client, accessLog) with LibraryTypeaheadStore

class InMemoryLibraryTypeaheadStoreImpl extends InMemoryPrefixFilterStoreImpl[User, Library] with LibraryTypeaheadStore

class LibraryFilterTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[LibraryFilterTypeaheadKey, PrefixFilter[Library]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibraryFilterTypeaheadKey(userId: Id[User]) extends Key[PrefixFilter[Library]] {
  val namespace = "library_typeahead"
  override val version = 1
  def toKey(): String = userId.id.toString
}

class LibraryResultTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryResultTypeaheadKey, LibraryTypeaheadResult](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibraryResultTypeaheadKey(userId: Id[User], libraryId: Id[Library]) extends Key[LibraryTypeaheadResult] {
  val namespace = "library_typeahead_result"
  override val version = 1
  def toKey(): String = s"${userId.id.toString}:${libraryId.id.toString}"
}

