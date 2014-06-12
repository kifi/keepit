package com.keepit.heimdal

import com.keepit.common.cache.CacheModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.google.inject.{Provides, Singleton}
import com.amazonaws.auth.BasicAWSCredentials
import com.kifi.franz.QueueName
import play.api.Play._
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule

abstract class HeimdalModule(
  // Common Functional Modules
  val cacheModule: CacheModule,
  val mongoModule: MongoModule
) extends ConfigurationModule with CommonServiceModule  {
  // Service clients
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
}
