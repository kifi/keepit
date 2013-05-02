package com.keepit.common.store

import scala.collection.mutable.HashMap
import scala.concurrent.promise

import com.keepit.common.db.Id
import com.keepit.common.social.SocialUserRawInfo
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.model.{User, NormalizedURI, SocialUserInfo}
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.tzavellas.sse.guice.ScalaModule

case class FakeStoreModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[ArticleStore].toInstance(new HashMap[Id[NormalizedURI], Article] with ArticleStore)
    bind[SocialUserRawInfoStore].toInstance(new HashMap[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore)
    bind[S3ImageStore].toInstance(new S3ImageStore {
      def cdnBase = "http://localhost"
      def getPictureUrl(w: Int, user: User) =
        promise[String]().success(s"http://localhost/${user.id.get}_${w}x${w}").future
    })
  }

}
