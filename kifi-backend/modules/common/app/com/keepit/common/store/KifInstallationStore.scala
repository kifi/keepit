package com.keepit.common.store

import com.keepit.common.logging.AccessLog
import com.keepit.model.KifiVersion
import com.amazonaws.services.s3._
import play.api.libs.json._
import java.util.concurrent.{Callable, TimeUnit}
import com.google.common.cache.{CacheLoader, CacheBuilder}
import play.api.libs.json.JsSuccess
import com.google.common.util.concurrent.{ListenableFutureTask, ListenableFuture}

case class KifiInstallationDetails(gold: KifiVersion, killed: Seq[KifiVersion])

trait KifInstallationStore extends ObjectStore[String, KifiInstallationDetails] {
  protected val defaultValue = KifiInstallationDetails(KifiVersion("2.8.55"), Nil)
  def get(): KifiInstallationDetails
  def getRaw(): KifiInstallationDetails
  def set(newDetails: KifiInstallationDetails)
}

class S3KifInstallationStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val formatter: Format[KifiInstallationDetails] = S3KifInstallationStoreImpl.detailsFormat)
  extends S3JsonStore[String, KifiInstallationDetails] with KifInstallationStore {
  private val s3Get = (id: String) => super.get(id)
  private val kifiInstallationKey = "browser_extension"

  private val cachedValue = CacheBuilder.newBuilder().concurrencyLevel(1).maximumSize(4).refreshAfterWrite(5, TimeUnit.MINUTES).expireAfterWrite(10, TimeUnit.MINUTES).build(new CacheLoader[String, KifiInstallationDetails] {
    override def load(key: String): KifiInstallationDetails = {
      s3Get(key).getOrElse(defaultValue)
    }
    override def reload(key: String, prev: KifiInstallationDetails): ListenableFuture[KifiInstallationDetails] = {
      ListenableFutureTask.create(new Callable[KifiInstallationDetails] {
        def call(): KifiInstallationDetails = {
          s3Get(key).getOrElse(defaultValue)
        }
      })
    }
  })

  override def get(id: String): Option[KifiInstallationDetails] = {
    Some(cachedValue(id))
  }
  def get(): KifiInstallationDetails = cachedValue(kifiInstallationKey)
  def getRaw(): KifiInstallationDetails = s3Get(kifiInstallationKey).getOrElse(defaultValue)
  def set(newDetails: KifiInstallationDetails) = {
    super.+=(kifiInstallationKey, newDetails)
    cachedValue.put(kifiInstallationKey, newDetails)
  }

  get() // load the cache
}

object S3KifInstallationStoreImpl {
  implicit val versionFormat = new Format[KifiVersion] {
    def reads(json: JsValue) = JsSuccess(KifiVersion(json.as[String]))
    def writes(version: KifiVersion) = JsString(version.toString)
  }
  implicit val detailsFormat = Json.format[KifiInstallationDetails]
}

class InMemoryKifInstallationStoreImpl extends InMemoryObjectStore[String, KifiInstallationDetails] with KifInstallationStore {
  private val kifiInstallationKey = "browser_extension"
  def get(): KifiInstallationDetails = get(kifiInstallationKey).get
  def getRaw(): KifiInstallationDetails = get()
  def set(newDetails: KifiInstallationDetails) = {
    super.+=(kifiInstallationKey, newDetails)
  }
  get() // load the cache
}

