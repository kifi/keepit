package com.keepit.common.store

import scala.collection.mutable.HashMap
import scala.concurrent.promise

import com.amazonaws.services.s3.model.PutObjectResult
import com.google.inject.{Singleton, Provides}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.social.SocialUserRawInfo
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.model.{User, NormalizedURI, SocialUserInfo}
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.tzavellas.sse.guice.ScalaModule

case class FakeS3StoreModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[ArticleStore].toInstance(new HashMap[Id[NormalizedURI], Article] with ArticleStore)
    bind[SocialUserRawInfoStore].toInstance(new HashMap[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore)
    bind[S3ImageConfig].toInstance(S3ImageConfig("test-bucket", "http://localhost", isLocal = true))
  }

  @Provides @Singleton
  def s3ImageStore(s3ImageConfig: S3ImageConfig): S3ImageStore = new S3ImageStore {
    def config = s3ImageConfig
    def updatePicture(sui: SocialUserInfo, externalId: ExternalId[User]) =
      promise[Seq[PutObjectResult]]().success(Seq()).future
    def getPictureUrl(w: Int, user: User) =
      promise[String]().success(s"http://cloudfront/${user.id.get}_${w}x${w}").future
  }
}
