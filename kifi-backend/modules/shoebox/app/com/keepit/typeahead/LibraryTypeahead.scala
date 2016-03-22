package com.keepit.typeahead

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Provider, Inject }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.cache.{ BinaryCacheImpl, CacheStatistics, FortyTwoCachePlugin, Key }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.store.S3Bucket
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.typeahead.LibraryTypeahead.UserLibraryTypeahead
import org.joda.time.Minutes

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object LibraryTypeahead {
  type UserLibraryTypeahead = PersonalTypeahead[User, Library, Library]
}

class LibraryTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    store: LibraryTypeaheadStore,
    cache: LibraryTypeaheadCache,
    libraryInfoCommander: Provider[LibraryInfoCommander],
    libraryRepo: LibraryRepo,
    implicit val ec: ExecutionContext,
    implicit val config: PublicIdConfiguration) extends Typeahead[User, Library, Library, UserLibraryTypeahead] with Logging {

  protected val refreshRequestConsolidationWindow = 10 minutes

  protected val fetchRequestConsolidationWindow = 15 seconds

  protected def get(userId: Id[User]) = {
    val filterOpt = cache.getOrElseOpt(LibraryTypeaheadKey(userId)) {
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

  private def getInfos(userId: Id[User])(ids: Seq[Id[Library]]): Future[Seq[Library]] = {
    if (ids.isEmpty) Future.successful(Seq.empty[Library])
    else {
      SafeFuture {
        val idSet = ids.toSet
        db.readOnlyReplica { implicit session =>
          val libs = libraryRepo.getActiveByIds(idSet)
          libs.values.toSeq
        }
      }
    }
  }

  private def getAllInfos(id: Id[User]): Future[Seq[(Id[Library], Library)]] = SafeFuture {
    db.readOnlyReplica { implicit session =>
      libraryInfoCommander.get.getLibrariesUserCanKeepTo(id, includeOrgLibraries = true).map { case (l, _, _) => l.id.get -> l }
    }
  }

  protected def extractName(info: Library): String = info.name

  protected def invalidate(typeahead: UserLibraryTypeahead) = {
    val userId = typeahead.ownerId
    val filter = typeahead.filter
    cache.set(LibraryTypeaheadKey(userId), filter)
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

class LibraryTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[LibraryTypeaheadKey, PrefixFilter[Library]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibraryTypeaheadKey(userId: Id[User]) extends Key[PrefixFilter[Library]] {
  val namespace = "library_typeahead"
  override val version = 1
  def toKey(): String = userId.id.toString
}

