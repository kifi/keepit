package com.keepit.common.social

import scala.collection.mutable.{Map => MutableMap}
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.serializer.ArticleSerializer
import com.keepit.inject._
import com.keepit.common.store.ObjectStore
import com.keepit.common.store.S3ObjectStore
import com.keepit.common.store.S3Bucket
import com.keepit.model.SocialUserInfo
import com.amazonaws.auth._
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.S3Object
import java.io.{InputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.lang.UnsupportedOperationException
import play.api.Play.current
import play.api.libs.json.Format


trait SocialUserRawInfoStore extends ObjectStore[SocialUserInfo, SocialUserRawInfo]

class S3SocialUserRawInfoStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, 
    val formatter: Format[SocialUserRawInfo]) 
  extends S3ObjectStore[SocialUserInfo, SocialUserRawInfo] with SocialUserRawInfoStore