package com.keepit.rover.sensitivity

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ InMemoryObjectStore, ObjectStore, S3Bucket, S3JsonStore }
import play.api.libs.json.{ Format, _ }

case class PornWordLikelihood(likelihood: Map[String, Float])

object PornWordLikelihood {
  implicit val format = Json.format[PornWordLikelihood]
}

trait PornWordLikelihoodStore extends ObjectStore[String, PornWordLikelihood]

class S3PornWordLikelihoodStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val formatter: Format[PornWordLikelihood] = PornWordLikelihood.format)
    extends S3JsonStore[String, PornWordLikelihood] with PornWordLikelihoodStore {
  val prefix = "bayes_porn_detector/"
  override def keyPrefix() = prefix
}

class InMemoryPornWordLikelihoodStore extends InMemoryObjectStore[String, PornWordLikelihood] with PornWordLikelihoodStore
