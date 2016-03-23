package com.keepit.typeahead

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provider, Inject }
import com.keepit.commanders._
import com.keepit.common.core._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.cache._
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
import org.joda.time.Minutes

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object LibraryTypeahead {
  type UserLibraryTypeahead = PersonalTypeahead[User, Library, LibraryTypeaheadResult]
}

@json case class LibraryTypeaheadResult(id: Id[Library], name: String, importance: Int) {
  // importance is a measure to assist in ranking of libraries not recently used by the user. Sum of (lowest is best):
  //  • -2 active membership
  //  • -1 visited previously
  //  • -1 has keeps
  //  • -1 keeps in past 3 days
  //  • -1 keeps in past 15 days
  //  • -1 user created
}

class LibraryTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    store: LibraryTypeaheadStore,
    cache: LibraryFilterTypeaheadCache,
    libResCache: LibraryResultTypeaheadCache,
    libraryInfoCommander: Provider[LibraryInfoCommander],
    libraryMembershipRepo: LibraryMembershipRepo,
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
          if (meta.exists(m => m.lastModified.plusMinutes(15).isBefore(currentDateTime))) {
            log.info(s"[asyncGetOrCreatePrefixFilter($userId)] filter EXPIRED (lastModified=${meta.get.lastModified}); (curr=$currentDateTime); (delta=${Minutes.minutesBetween(meta.get.lastModified, currentDateTime)} minutes) - rebuild")
            refresh(userId)
          }
          filter
      }
    }
    Future.successful(filterOpt.map(filter => PersonalTypeahead(userId, filter, getInfos(userId))))
  }

  private def getInfos(userId: Id[User])(ids: Seq[Id[Library]]): Future[Seq[LibraryTypeaheadResult]] = {
    SafeFuture {
      val idSet = ids.toSet
      log.info(s"[LibraryTypeahead#getInfos] $userId $ids")
      libResCache(directCacheAccess).bulkGetOrElse(idSet.map(i => LibraryResultTypeaheadKey(userId, i))) { missingKeys =>
        log.info(s"[LibraryTypeahead#getInfos:missing] Don't have ${missingKeys.map(_.libraryId)}")
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

  private def getAllInfos(id: Id[User]): Future[Seq[(Id[Library], LibraryTypeaheadResult)]] = SafeFuture {
    db.readOnlyReplica { implicit session =>
      val r = libraryInfoCommander.get.getLibrariesUserCanKeepTo(id, includeOrgLibraries = true)
      log.info(s"[LibraryTypeahead#all] $id ${r.length}: ${r.map(_._1.id.get).mkString(",")}")
      r
    }.map {
      case (l, mOpt, _) =>
        val res = LibraryTypeaheadResult(l.id.get, l.name, calcImportance(l, mOpt))
        libResCache(directCacheAccess).set(LibraryResultTypeaheadKey(id, l.id.get), res)
        l.id.get -> res
    } tap { r =>
      log.info(s"[LibraryTypeahead#allDone] $id ${r.length}")
    }
  }

  private def calcImportance(lib: Library, memOpt: Option[LibraryMembership]): Int = {
    Seq(
      memOpt.isDefined,
      memOpt.isDefined,
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
    getAllInfos(userId).map { allInfos =>
      val filter = buildFilter(userId, allInfos)
      log.info(s"[refresh($userId)] cache/store updated; filter=$filter")
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

