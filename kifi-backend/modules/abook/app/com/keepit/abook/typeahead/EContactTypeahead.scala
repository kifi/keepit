package com.keepit.abook.typeahead

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.abook.ABookInfoRepo
import scala.concurrent.{ Future }
import com.keepit.common.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import com.keepit.typeahead._
import org.joda.time.Minutes
import com.keepit.common.time._
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.common.cache.{ Key, BinaryCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.store.S3Bucket
import com.keepit.abook.model.{ EContactRepo, EContact }
import com.keepit.common.mail.EmailAddress

class EContactTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    cache: EContactTypeaheadCache,
    store: EContactTypeaheadStore,
    abookInfoRepo: ABookInfoRepo,
    econtactRepo: EContactRepo) extends Typeahead[User, EContact, EContact] with Logging {

  import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

  val MYSQL_MAX_ROWS = 50000000

  override protected def extractName(info: EContact): String = {
    val name = info.name.getOrElse("").trim
    s"$name ${info.email.address}"
  }

  def refresh(userId: Id[User]): Future[PrefixFilter[EContact]] = {
    build(userId).map { filter =>
      cache.set(EContactTypeaheadKey(userId), filter)
      store += (userId -> filter)
      log.info(s"[rebuild($userId)] cache/store updated; filter=$filter")
      filter
    }(ExecutionContext.fj)
  }

  protected def getOrCreatePrefixFilter(userId: Id[User]): Future[PrefixFilter[EContact]] = {
    cache.getOrElseFuture(EContactTypeaheadKey(userId)) {
      val res = store.getWithMetadata(userId)
      res match {
        case Some((filter, meta)) =>
          if (meta.exists(m => m.lastModified.plusMinutes(15).isBefore(currentDateTime))) {
            log.info(s"[asyncGetOrCreatePrefixFilter($userId)] filter EXPIRED (lastModified=${meta.get.lastModified}); (curr=${currentDateTime}); (delta=${Minutes.minutesBetween(meta.get.lastModified, currentDateTime)} minutes) - rebuild")
            refresh(userId) // async
          }
          Future.successful(filter) // return curr one
        case None => refresh(userId)
      }
    }
  }

  def refreshAll(): Future[Unit] = {
    log.info("[refreshAll] begin re-indexing ...")
    val abookInfos = db.readOnlyReplica { implicit ro =>
      abookInfoRepo.all() // only retrieve users with existing abooks (todo: deal with deletes)
    }
    log.info(s"[refreshAll] ${abookInfos.length} to be re-indexed; abooks=${abookInfos.take(20).mkString(",")} ...")
    val userIds = abookInfos.foldLeft(Set.empty[Id[User]]) { (a, c) => a + c.userId } // inefficient
    refreshByIds(userIds.toSeq).map { _ =>
      log.info(s"[refreshAll] re-indexing finished.")
    }(ExecutionContext.fj)
  }

  protected def getAllInfos(id: Id[User]): Future[Seq[(Id[EContact], EContact)]] = {
    db.readOnlyMasterAsync { implicit ro =>
      econtactRepo.getByUserId(id).filter(contact => EmailAddress.isLikelyHuman(contact.email)).map(contact => contact.id.get -> contact)
    }
  }

  protected def getInfos(userId: Id[User], ids: Seq[Id[EContact]]): Future[Seq[EContact]] = {
    if (ids.isEmpty) Future.successful(Seq.empty) else {
      db.readOnlyMasterAsync { implicit ro =>
        econtactRepo.bulkGetByIds(ids).valuesIterator.toSeq
      }
    }
  }
}

trait EContactTypeaheadStore extends PrefixFilterStore[User, EContact]

class S3EContactTypeaheadStore @Inject() (
  bucket: S3Bucket,
  amazonS3Client: AmazonS3,
  accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User, EContact](bucket, amazonS3Client, accessLog) with EContactTypeaheadStore

class InMemoryEContactTypeaheadStore extends InMemoryPrefixFilterStoreImpl[User, EContact] with EContactTypeaheadStore

class EContactTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[EContactTypeaheadKey, PrefixFilter[EContact]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class EContactTypeaheadKey(userId: Id[User]) extends Key[PrefixFilter[EContact]] {
  val namespace = "econtact_typeahead"
  override val version = 1
  def toKey() = userId.id.toString
}
