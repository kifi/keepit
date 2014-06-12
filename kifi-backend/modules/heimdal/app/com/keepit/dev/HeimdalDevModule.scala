package com.keepit.dev

import com.keepit.heimdal.{HeimdalEvent, HeimdalModule}
import com.keepit.inject.CommonDevModule
import com.google.inject.{Provides, Singleton}
import com.amazonaws.auth.BasicAWSCredentials
import com.kifi.franz.{FakeSQSQueue, SQSQueue}
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.heimdal.DevMongoModule
import com.keepit.common.cache.HeimdalCacheModule
import net.codingwell.scalaguice.ScalaModule

case class HeimdalDevModule() extends HeimdalModule(
  cacheModule = HeimdalCacheModule(HashMapMemoryCacheModule()),
  mongoModule = DevMongoModule()
) with CommonDevModule {

  override def preConfigure(): Unit = {
    install(HeimdalFakeQueueModule())
  }
}

case class HeimdalFakeQueueModule() extends ScalaModule {

  override def configure(): Unit = {}

  @Singleton
  @Provides
  def heimdalEventQueue(): SQSQueue[HeimdalEvent] = {
    new FakeSQSQueue[HeimdalEvent]{}
  }

}
