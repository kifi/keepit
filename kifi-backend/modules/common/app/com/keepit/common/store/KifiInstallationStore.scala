package com.keepit.common.store

import com.keepit.common.logging.AccessLog
import com.keepit.model.{ KifiExtVersion, KifiVersion }
import com.amazonaws.services.s3._
import play.api.libs.json._
import java.util.concurrent.{ Callable, TimeUnit }
import com.google.common.cache.{ CacheLoader, CacheBuilder }
import play.api.libs.json.JsSuccess
import com.google.common.util.concurrent.{ ListenableFutureTask, ListenableFuture }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.PimpMyFuture._
import play.api.libs.concurrent.Execution.Implicits._

case class KifiInstallationDetails(gold: KifiExtVersion, killed: Seq[KifiExtVersion])

trait KifiInstallationStore extends ObjectStore[String, KifiInstallationDetails] {
  protected val defaultValue = KifiInstallationDetails(KifiExtVersion("2.8.55"), Nil)
  def get(): KifiInstallationDetails
  def getRaw(): KifiInstallationDetails
  def set(newDetails: KifiInstallationDetails)
}

class S3KifiInstallationStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val formatter: Format[KifiInstallationDetails] = S3KifiInstallationStoreImpl.detailsFormat)
    extends S3JsonStore[String, KifiInstallationDetails] with KifiInstallationStore {
  private val s3Get = (id: String) => super.syncGet(id)
  private val kifiInstallationKey = "browser_extension"

  private val cachedValue = CacheBuilder.newBuilder().concurrencyLevel(1).maximumSize(1).refreshAfterWrite(5, TimeUnit.MINUTES).expireAfterWrite(10, TimeUnit.MINUTES).build(new CacheLoader[String, KifiInstallationDetails] {
    override def load(key: String): KifiInstallationDetails = {
      log.info("Loading KifiInstallationStore. Giving default value :(")
      defaultValue
    }
    override def reload(key: String, prev: KifiInstallationDetails): ListenableFuture[KifiInstallationDetails] = {
      log.info(s"Reloading KifiInstallationStore. Current gold: ${prev.gold.toString}")
      SafeFuture(s3Get(key).getOrElse(defaultValue)).map { v =>
        log.info(s"Reloading KifiInstallationStore complete. New gold: ${v.gold.toString}")
        v
      }.asListenableFuture
    }
  })

  override def syncGet(id: String): Option[KifiInstallationDetails] = {
    Some(cachedValue.get(id))
  }
  def get(): KifiInstallationDetails = cachedValue.get(kifiInstallationKey)
  def getRaw(): KifiInstallationDetails = s3Get(kifiInstallationKey).getOrElse(defaultValue)
  def set(newDetails: KifiInstallationDetails) = {
    super.+=(kifiInstallationKey, newDetails)
    cachedValue.put(kifiInstallationKey, newDetails)
  }

  cachedValue.put(kifiInstallationKey, defaultValue)
  SafeFuture {
    log.info("First time load of KifiInstallationStore.")
    val loaded = s3Get(kifiInstallationKey).getOrElse(defaultValue)
    cachedValue.put(kifiInstallationKey, loaded)
    log.info(s"First time load of KifiInstallationStore complete. New gold version: ${loaded.gold.toString}")
  }
}

object S3KifiInstallationStoreImpl {
  implicit val versionFormat = new Format[KifiExtVersion] {
    def reads(json: JsValue) = JsSuccess(KifiExtVersion(json.as[String]))
    def writes(version: KifiExtVersion) = JsString(version.toString)
  }
  implicit val detailsFormat = Json.format[KifiInstallationDetails]
}

class InMemoryKifiInstallationStoreImpl extends InMemoryObjectStore[String, KifiInstallationDetails] with KifiInstallationStore {
  private val kifiInstallationKey = "browser_extension"
  def get(): KifiInstallationDetails = syncGet(kifiInstallationKey).getOrElse(defaultValue)
  def getRaw(): KifiInstallationDetails = get()
  def set(newDetails: KifiInstallationDetails) = {
    super.+=(kifiInstallationKey, newDetails)
  }
  get() // load the cache
}

