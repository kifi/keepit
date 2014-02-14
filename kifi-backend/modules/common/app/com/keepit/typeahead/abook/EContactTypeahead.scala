package com.keepit.typeahead.abook

import com.keepit.typeahead._
import com.keepit.model.{User, EContact}
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
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.healthcheck.AirbrakeNotifier

class EContactTypeahead @Inject() (
  airbrake:AirbrakeNotifier,
  override val store: EContactTypeaheadStore,
  cache: EContactTypeaheadCache,
  abookClient:ABookServiceClient
)extends Typeahead[EContact, EContact] with Logging {

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

  override protected def asyncGetAllInfosForUser(id: Id[User]):Future[Seq[EContact]] = abookClient.getEContacts(id, 50000000) // MySQL limit

  override protected def getAllInfosForUser(id: Id[User]):Seq[EContact] = {
    Await.result(asyncGetAllInfosForUser(id), 1 minute) // todo(ray):revisit
  }

  override protected def asyncGetInfos(ids:Seq[Id[EContact]]):Future[Seq[EContact]] = abookClient.getEContactsByIds(ids)

  override protected def getInfos(ids: Seq[Id[EContact]]):Seq[EContact] ={
    Await.result(asyncGetInfos(ids), 1 minute) // todo(ray):revisit
  }

  override protected def getPrefixFilter(userId: Id[User]):Option[PrefixFilter[EContact]] = {
    val res = cache.getOrElseOpt(EContactTypeaheadKey(userId)) { store.get(userId) } map { new PrefixFilter[EContact](_) }
    log.info(s"[getPrefixFilter($userId)] res=$res")
    res
  }
}

trait EContactTypeaheadStore extends PrefixFilterStore[User]

class S3EContactTypeaheadStore @Inject()(
  bucket:S3Bucket,
  amazonS3Client:AmazonS3,
  accessLog:AccessLog) extends S3PrefixFilterStoreImpl[User](bucket, amazonS3Client, accessLog) with EContactTypeaheadStore

class InMemoryEContactTypeaheadStore extends InMemoryPrefixFilterStoreImpl[User] with EContactTypeaheadStore

class EContactTypeaheadCache(stats:CacheStatistics, accessLog:AccessLog, innermostPluginSettings:(FortyTwoCachePlugin, Duration))
  extends BinaryCacheImpl[EContactTypeaheadKey, Array[Long]](stats, accessLog, innermostPluginSettings)(ArrayBinaryFormat.longArrayFormat)

case class EContactTypeaheadKey(userId: Id[User]) extends Key[Array[Long]] {
  val namespace = "econtact_typeahead"
  override val version = 1
  def toKey() = userId.id.toString
}