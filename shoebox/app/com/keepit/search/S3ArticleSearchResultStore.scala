package com.keepit.search

import scala.collection.mutable.{Map => MutableMap}
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.serializer.ArticleSerializer
import com.keepit.inject._
import play.api.Play.current
import com.amazonaws.auth._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import java.io.{InputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.lang.UnsupportedOperationException
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.S3Object
import com.keepit.common.store.ObjectStore
import com.keepit.common.store.S3ObjectStore
import com.keepit.common.store.S3Bucket
import play.api.libs.json.Format
import com.keepit.serializer.ArticleSearchResultSerializer
import com.keepit.common.db.ExternalId
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Await
import com.keepit.common.db.CX
import com.keepit.model.SocialUserInfo


trait ArticleSearchResultStore extends ObjectStore[ExternalId[ArticleSearchResultRef], ArticleSearchResult]

class S3ArticleSearchResultStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val formatter: Format[ArticleSearchResult] = new ArticleSearchResultSerializer())
  extends S3ObjectStore[ExternalId[ArticleSearchResultRef], ArticleSearchResult] with ArticleSearchResultStore
