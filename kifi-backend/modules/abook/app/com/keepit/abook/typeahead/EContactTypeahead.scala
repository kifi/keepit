package com.keepit.abook.typeahead

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ User }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.abook.{ ABookInfoRepo }
import scala.concurrent.{ Future }
import com.keepit.common.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import com.keepit.typeahead._
import org.joda.time.Minutes
import com.keepit.common.time._
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.common.cache.{ Key, BinaryCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.serializer.ArrayBinaryFormat
import com.keepit.common.store.S3Bucket
import com.keepit.abook.model.{ EContactRepo, EContact }

class EContactTypeahead @Inject() (
    db: Database,
    override val airbrake: AirbrakeNotifier,
    cache: EContactTypeaheadCache,
    store: EContactTypeaheadStore,
    abookInfoRepo: ABookInfoRepo,
    econtactRepo: EContactRepo) extends Typeahead[EContact, EContact] with Logging {

  import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

  val MYSQL_MAX_ROWS = 50000000

  override protected def extractName(info: EContact): String = {
    val name = info.name.getOrElse("").trim
    s"$name ${info.email.address}"
  }

  override protected def extractId(info: EContact): Id[EContact] = info.id.get

  def refresh(userId: Id[User]): Future[PrefixFilter[EContact]] = {
    build(userId).map { filter =>
      cache.set(EContactTypeaheadKey(userId), filter.data)
      store += (userId -> filter.data)
      log.info(s"[rebuild($userId)] cache/store updated; filter=$filter")
      filter
    }(ExecutionContext.fj)
  }

  protected def asyncGetOrCreatePrefixFilter(userId: Id[User]): Future[PrefixFilter[EContact]] = {
    cache.getOrElseFuture(EContactTypeaheadKey(userId)) {
      val res = store.getWithMetadata(userId)
      res match {
        case Some((filter, meta)) =>
          if (meta.exists(m => m.lastModified.plusMinutes(15).isBefore(currentDateTime))) {
            log.info(s"[asyncGetOrCreatePrefixFilter($userId)] filter EXPIRED (lastModified=${meta.get.lastModified}); (curr=${currentDateTime}); (delta=${Minutes.minutesBetween(meta.get.lastModified, currentDateTime)} minutes) - rebuild")
            refresh(userId) // async
          }
          Future.successful(filter) // return curr one
        case None => refresh(userId).map { _.data }(ExecutionContext.fj)
      }
    }.map { new PrefixFilter[EContact](_) }(ExecutionContext.fj)
  }

  def refreshAll(): Future[Unit] = {
    log.info("[refreshAll] begin re-indexing ...")
    val abookInfos = db.readOnlyMaster { implicit ro =>
      abookInfoRepo.all() // only retrieve users with existing abooks (todo: deal with deletes)
    }
    log.info(s"[refreshAll] ${abookInfos.length} to be re-indexed; abooks=${abookInfos.take(20).mkString(",")} ...")
    val userIds = abookInfos.foldLeft(Set.empty[Id[User]]) { (a, c) => a + c.userId } // inefficient
    refreshByIds(userIds.toSeq).map { _ =>
      log.info(s"[refreshAll] re-indexing finished.")
    }(ExecutionContext.fj)
  }

  override protected def getAllInfosForUser(id: Id[User]): Seq[EContact] = {
    db.readOnlyMaster(attempts = 2) { implicit ro =>
      econtactRepo.getByUserId(id)
    }.filter(EContactTypeahead.isLikelyHuman)
  }

  override protected def getInfos(ids: Seq[Id[EContact]]): Seq[EContact] = {
    if (ids.isEmpty) Seq.empty[EContact]
    else {
      db.readOnlyMaster(attempts = 2) { implicit ro =>
        econtactRepo.bulkGetByIds(ids).valuesIterator.toSeq
      }
    }
  }
}

object EContactTypeahead {
  // might also consider these indicators in the future:
  // support feedback comment notification tickets? bugs? buganizer system nobody lists? announce(ments?)?
  // discuss help careers jobs reports? bounces? updates?
  val botEmailAddressRe = """(?:\+[^@]|\d{10}|\b(?i)(?:(?:no)?reply|(?:un)?subscribe)\b)""".r
  protected[typeahead] def isLikelyHuman(contact: EContact): Boolean = {
    botEmailAddressRe.findFirstIn(contact.email.address).isEmpty
  }
}

trait EContactTypeaheadStore extends PrefixFilterStore[User]

class S3EContactTypeaheadStore @Inject() (
  bucket: S3Bucket,
  amazonS3Client: AmazonS3,
  accessLog: AccessLog) extends S3PrefixFilterStoreImpl[User](bucket, amazonS3Client, accessLog) with EContactTypeaheadStore

class InMemoryEContactTypeaheadStore extends InMemoryPrefixFilterStoreImpl[User] with EContactTypeaheadStore

class EContactTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[EContactTypeaheadKey, Array[Long]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(ArrayBinaryFormat.longArrayFormat)

case class EContactTypeaheadKey(userId: Id[User]) extends Key[Array[Long]] {
  val namespace = "econtact_typeahead"
  override val version = 1
  def toKey() = userId.id.toString
}
