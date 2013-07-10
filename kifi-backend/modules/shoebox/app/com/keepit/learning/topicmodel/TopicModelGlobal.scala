package com.keepit.learning.topicmodel

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provider, Singleton, Provides}
import com.keepit.common.logging.Logging
import play.api.Play._
import com.keepit.inject.AppScoped
import com.keepit.model.TopicNameRepoA
import com.keepit.common.db.slick.Database
import scala.concurrent._
import ExecutionContext.Implicits.global

object TopicModelGlobal {
  val numTopics = 100
  val primaryTopicThreshold = 0.07       // need to tune this as numTopics varies
  val topicTailcut = 0.7
  val naString = "NA"
}

trait TopicModelModule extends ScalaModule {
  def configure {}
}

case class LdaTopicModelModule() extends TopicModelModule with Logging {
  override def configure() {
    bind[TopicUpdaterPlugin].to[TopicUpdaterPluginImpl].in[AppScoped]
    bind[WordTopicModelFactory].to[WordTopicModelFactoryImpl].in[AppScoped]
    bind[NameMapperFactory].to[NameMapperFactoryImpl].in[AppScoped]
  }

  @Provides
  @Singleton
  def switchableTopicModelAccessor(factory: SwitchableTopicModelAccessorFactory): SwitchableTopicModelAccessor = {
    val a = future{ factory.makeA() }
    val b = future{ factory.makeB() }
    new SwitchableTopicModelAccessor(a, b)
  }

}

case class DevTopicModelModule() extends TopicModelModule {
  override def configure() {
    bind[TopicUpdaterPlugin].to[TopicUpdaterPluginImpl].in[AppScoped]
    bind[WordTopicModelFactory].to[FakeWordTopicModelFactoryImpl].in[AppScoped]
    // bind[WordTopicModelFactory].to[WordTopicModelFactoryImpl].in[AppScoped]        // uncomment to connect to S3 in dev mode
    bind[NameMapperFactory].to[FakeNameMapperFactoryImpl].in[AppScoped]
  }

  @Provides
  @Singleton
  def switchableTopicModelAccessor(factory: SwitchableTopicModelAccessorFactory): SwitchableTopicModelAccessor = {
    val a = future{ factory.makeA() }
    val b = future{ factory.makeB() }
    new SwitchableTopicModelAccessor(a, b)
  }
}
