package com.keepit.typeahead.abook

import com.keepit.typeahead._
import com.keepit.model.{EContactKey, EContactCache, User, EContact}
import com.google.inject.Inject
import com.keepit.common.logging.{Logging, AccessLog}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.cache.{Key, BinaryCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import com.keepit.common.db.Id
import com.keepit.serializer.ArrayBinaryFormat
import com.keepit.abook.{EmailParser, ABookServiceClient}
import com.keepit.common.store.S3Bucket
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.{Duration, DurationInt}
//import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.healthcheck.AirbrakeNotifier

class EContactTypeahead @Inject() (
  airbrake:AirbrakeNotifier,
  store: EContactTypeaheadStore,
  typeaheadCache: EContactTypeaheadCache,
  econtactCache: EContactCache,
  abookClient:ABookServiceClient
)extends Typeahead[EContact, EContact] with Logging {

  val MYSQL_MAX_ROWS = 50000000

  override protected def extractName(info: EContact):String = { // todo(ray): revisit
    val name = info.name.getOrElse("").trim
    val pres = EmailParser.parse(EmailParser.email, info.email)
    if (pres.successful) {
      s"$name ${pres.get.local.toStrictString}"
    } else {
      airbrake.notify(s"[EContactTypeahead.extractName($info)] Failed to parse email ${info.email}")
      val local = info.email.takeWhile(c => c != '@').trim // best effort
      s"$name $local"
    }
  }

  override protected def extractId(info: EContact):Id[EContact] = info.id.get

  override protected def asyncGetAllInfosForUser(id: Id[User]):Future[Seq[EContact]] = abookClient.getEContacts(id, MYSQL_MAX_ROWS) // MySQL limit

  override protected def getAllInfosForUser(id: Id[User]):Seq[EContact] = {
    Await.result(asyncGetAllInfosForUser(id), Duration.Inf) // todo(ray):revisit
  }

  override protected def asyncGetInfos(ids:Seq[Id[EContact]]):Future[Seq[EContact]] = {
    implicit val fjCtx = com.keepit.common.concurrent.ExecutionContext.fj
    if (ids.isEmpty) Future.successful(Seq.empty[EContact])
    else {
      val s3F = econtactCache.bulkGetOrElseFuture(ids.map(EContactKey(_)).toSet) { keys =>
        val missing = keys.map(_.id)
        log.info(s"[asyncGetInfos(${ids.length};${ids.take(10).mkString(",")})-S3] missing(len=${missing.size}):${missing.take(10).mkString(",")}")
        val res = abookClient.getEContactsByIds(missing.toSeq).map { res =>
          res.map(e => EContactKey(e.id.get) -> e).toMap
        }
        res
       }
      val localF = s3F map { res =>
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

  override protected def getInfos(ids: Seq[Id[EContact]]):Seq[EContact] = {
    Await.result(asyncGetInfos(ids), Duration.Inf) // todo(ray):revisit
  }

  def getPrefixFilter(userId: Id[User]):Option[PrefixFilter[EContact]] = {
    // cache.getOrElseOpt(EContactTypeaheadKey(userId)) { store.get(userId) } map { new PrefixFilter[EContact](_) }
    val (filter, msg) = typeaheadCache.get(EContactTypeaheadKey(userId)) match {
      case Some(filter) =>
        (Some(new PrefixFilter[EContact](filter)), "Cache.get")
      case None =>
        val (filter, msg) = store.get(userId) match {
          case Some(filter) =>
            (new PrefixFilter[EContact](filter), "Store.get")
          case None =>
            val pFilter = Await.result(build(userId), Duration.Inf)
            store += (userId -> pFilter.data)
            (pFilter, "Built")
        }
        typeaheadCache.set(EContactTypeaheadKey(userId), filter.data)
        (Some(filter), msg)
    }
    log.info(s"[email.getPrefixFilter($userId)] ($msg) ${filter}")
    filter
  }

  def refresh(userId: Id[User]): Future[Unit] = abookClient.refreshPrefixFilter(userId)
  def refreshByIds(ids: Seq[Id[User]]): Future[Unit] = abookClient.refreshPrefixFiltersByIds(ids)
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