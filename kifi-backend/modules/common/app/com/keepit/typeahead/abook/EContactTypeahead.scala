package com.keepit.typeahead.abook

import com.keepit.typeahead._
import com.keepit.model.{EContactKey, EContactCache, User, EContact}
import com.google.inject.Inject
import com.keepit.common.logging.{Logging, AccessLog}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.cache.{Key, BinaryCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import com.keepit.common.db.Id
import com.keepit.serializer.ArrayBinaryFormat
import com.keepit.abook.ABookServiceClient
import com.keepit.common.store.S3Bucket
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.{Duration, DurationInt}
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.EmailAddressParser
import play.api.Play
import org.joda.time.{Minutes, Seconds}
import scala.collection.mutable.ArrayBuffer

abstract class EContactTypeaheadBase(
  override val airbrake:AirbrakeNotifier,
  cache: EContactTypeaheadCache,
  store: EContactTypeaheadStore
)extends Typeahead[EContact, EContact] with Logging {

  val MYSQL_MAX_ROWS = 50000000

  override protected def extractName(info: EContact):String = {
  val name = info.name.getOrElse("").trim
    EmailAddressParser.parseOpt(info.email.address) match {
      case Some(addr) =>
        s"$name ${addr.toString}"
      case None =>
        airbrake.notify(s"[EContactTypeahead.extractName($info)] Failed to parse email ${info.email}")
        val addr = info.email.address.trim
        s"$name $addr"
    }
  }

  override protected def extractId(info: EContact):Id[EContact] = info.id.get

  override protected def getAllInfosForUser(id: Id[User]):Seq[EContact] = {
    Await.result(asyncGetAllInfosForUser(id), Duration.Inf)
  }

  override protected def getInfos(ids: Seq[Id[EContact]]):Seq[EContact] = {
    Await.result(asyncGetInfos(ids), Duration.Inf)
  }

  def refresh(userId:Id[User]):Future[PrefixFilter[EContact]] = {
    build(userId).map { filter =>
      cache.set(EContactTypeaheadKey(userId), filter.data)
      store += (userId -> filter.data)
      log.info(s"[rebuild($userId)] cache/store updated; filter=$filter")
      filter
    }(ExecutionContext.fj)
  }

  import com.keepit.common.time._
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
        case None => refresh(userId).map{ _.data }(ExecutionContext.fj)
      }
    }.map{ new PrefixFilter[EContact](_) }(ExecutionContext.fj)
  }

}

// "Remote"; uses abookServiceClient
class EContactTypeahead @Inject() (
  override val airbrake:AirbrakeNotifier,
  cache: EContactTypeaheadCache,
  store: EContactTypeaheadStore,
  econtactCache: EContactCache,
  abookClient:ABookServiceClient
)extends EContactTypeaheadBase(airbrake, cache, store) {

  override protected def asyncGetAllInfosForUser(id: Id[User]):Future[Seq[EContact]] = abookClient.getEContacts(id, MYSQL_MAX_ROWS) // MySQL limit

  override protected def asyncGetInfos(ids:Seq[Id[EContact]]):Future[Seq[EContact]] = {
    implicit val fj = ExecutionContext.fj
    if (ids.isEmpty) Future.successful(Seq.empty[EContact])
    else {
      val cacheF = econtactCache.bulkGetOrElseFuture(ids.map(EContactKey(_)).toSet) { keys =>
        val missing = keys.map(_.id)
        log.info(s"[asyncGetInfos(${ids.length};${ids.take(10).mkString(",")})-S3] missing(len=${missing.size}):${missing.take(10).mkString(",")}")
        val res = abookClient.getEContactsByIds(missing.toSeq).map { res =>
          res.map(e => EContactKey(e.id.get) -> e).toMap
        }
        res
       }
      val localF = cacheF map { res =>
        val v = res.valuesIterator.toVector
        log.info(s"[asyncGetInfos(${ids.length};${ids.take(10).mkString(",")})-S3] res=$v")
        v
      }
      val abookF = abookClient.getEContactsByIds(ids) map { res =>
        log.info(s"[asyncGetInfos(${ids.length};${ids.take(10).mkString(",")})-ABOOK] res=(${res.length});${res.take(10).mkString(",")}")
        res
      }
      Future.firstCompletedOf(Seq(localF, abookF))
    }
  }

  def refreshAll(): Future[Unit] = abookClient.refreshAllFilters()
}

trait EContactTypeaheadStore extends PrefixFilterStore[User]

class S3EContactTypeaheadStore @Inject()(
  bucket:S3Bucket,
  amazonS3Client:AmazonS3,
  accessLog:AccessLog) extends S3PrefixFilterStoreImpl[User](bucket, amazonS3Client, accessLog) with EContactTypeaheadStore

class InMemoryEContactTypeaheadStore extends InMemoryPrefixFilterStoreImpl[User] with EContactTypeaheadStore

class EContactTypeaheadCache(stats:CacheStatistics, accessLog:AccessLog, innermostPluginSettings:(FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[EContactTypeaheadKey, Array[Long]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(ArrayBinaryFormat.longArrayFormat)

case class EContactTypeaheadKey(userId: Id[User]) extends Key[Array[Long]] {
  val namespace = "econtact_typeahead"
  override val version = 1
  def toKey() = userId.id.toString
}
