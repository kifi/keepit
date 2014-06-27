package com.keepit.maven

import com.keepit.common.cache.CacheModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.social.RemoteSecureSocialModule


abstract class MavenModule(
  val cacheModule: CacheModule
) extends ConfigurationModule with CommonServiceModule {
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val cortexSlickModule = MavenSlickModule()
}
