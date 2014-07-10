package com.keepit.social

import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.store._
import com.keepit.model.SocialUserInfo
import com.amazonaws.services.s3._
import play.api.libs.json.Format
import com.keepit.serializer.SocialUserRawInfoSerializer

trait SocialUserRawInfoStore extends ObjectStore[Id[SocialUserInfo], SocialUserRawInfo]

class S3SocialUserRawInfoStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog,
  val formatter: Format[SocialUserRawInfo] = new SocialUserRawInfoSerializer())
    extends S3JsonStore[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore

class InMemorySocialUserRawInfoStoreImpl extends InMemoryObjectStore[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore
