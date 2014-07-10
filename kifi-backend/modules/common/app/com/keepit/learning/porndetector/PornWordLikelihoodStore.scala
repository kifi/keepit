package com.keepit.learning.porndetector

import com.keepit.common.store.S3JsonStore
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import com.keepit.common.store.InMemoryObjectStore
import play.api.libs.json.Format
import play.api.libs.json._
import com.keepit.common.store.ObjectStore
import play.api.libs.functional.syntax._

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
