package com.keepit.heimdal

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.google.inject.{Provides, Singleton}
import com.amazonaws.auth.BasicAWSCredentials
import com.kifi.franz.{SimpleSQSClient, QueueName, SQSQueue}
import play.api.Play._
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule
import com.amazonaws.regions.Regions

abstract class HeimdalModule(
  // Common Functional Modules
  val cacheModule: CacheModule,
  val mongoModule: MongoModule
) extends ConfigurationModule with CommonServiceModule  {
  // Service clients
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()

  @Singleton
  @Provides
  def heimdalEventQueue(basicAWSCreds:BasicAWSCredentials): SQSQueue[HeimdalEvent] = {
    val queueName = QueueName(current.configuration.getString("heimdal-events.queue-name").get)
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)
    client.formatted[HeimdalEvent](queueName)
  }
}
