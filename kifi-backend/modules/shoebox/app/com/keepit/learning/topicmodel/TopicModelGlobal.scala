package com.keepit.learning.topicmodel

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provider, Singleton, Provides}
import com.keepit.common.logging.Logging
import play.api.Play._
import com.keepit.inject.AppScoped
import com.keepit.model.TopicNameRepoA
import com.keepit.common.db.slick.Database

object TopicModelGlobal {
  val numTopics = 100
  val primaryTopicThreshold = 0.07       // need to tune this as numTopics varies
  val topicTailcut = 0.7
  val naString = "NA"
}

trait TopicModelModule extends ScalaModule {
  def configure {
    bind[TopicUpdaterPlugin].to[TopicUpdaterPluginImpl].in[AppScoped]
  }
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
    factory()
  }

}

case class DevTopicModelModule() extends TopicModelModule {
  override def configure() {
    bind[TopicUpdaterPlugin].to[TopicUpdaterPluginImpl].in[AppScoped]
    bind[WordTopicModelFactory].to[FakeWordTopicModelFactoryImpl].in[AppScoped]
    bind[NameMapperFactory].to[FakeNameMapperFactoryImpl].in[AppScoped]
  }

  @Provides
  @Singleton
  def switchableTopicModelAccessor(factory: SwitchableTopicModelAccessorFactory): SwitchableTopicModelAccessor = {
    factory()
  }
}
