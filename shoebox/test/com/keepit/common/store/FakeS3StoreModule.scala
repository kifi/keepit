package com.keepit.common.store

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject._
import com.google.inject.binder._
import akka.actor.Actor._
import akka.actor._
import scala.concurrent.Await
import akka.pattern.ask
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.Configuration
import scala.collection.mutable.MutableList
import com.keepit.search.ArticleStore
import scala.collection.mutable.HashMap
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.search.Article
import com.keepit.model.SocialUserInfo
import com.keepit.common.social.SocialUserRawInfoStore
import com.keepit.common.social.SocialUserRawInfo


case class FakeStoreModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[ArticleStore].toInstance(new HashMap[Id[NormalizedURI], Article] with ArticleStore)
    bind[SocialUserRawInfoStore].toInstance(new HashMap[Id[SocialUserInfo], SocialUserRawInfo] with SocialUserRawInfoStore)
  }

}
