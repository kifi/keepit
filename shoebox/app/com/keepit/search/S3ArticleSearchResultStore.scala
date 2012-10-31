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


trait ArticleSearchResultStore extends ObjectStore[ArticleSearchResultRef, ArticleSearchResult]

class ArticleSearchResultStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val formatter: Format[ArticleSearchResult] = new ArticleSearchResultSerializer()) 
  extends S3ObjectStore[ArticleSearchResultRef, ArticleSearchResult] with ArticleSearchResultStore