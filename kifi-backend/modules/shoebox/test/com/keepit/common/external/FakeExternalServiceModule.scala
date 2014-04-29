package com.keepit.common.external

import com.keepit.common.embedly.{DevEmbedlyClient, EmbedlyClient}
import com.keepit.common.pagepeeker.{DevPagePeekerClient, PagePeekerClient}
import com.google.inject.{Provides, Singleton}

case class FakeExternalServiceModule() extends ExternalServiceModule {

  def configure() {}

  @Provides
  @Singleton
  def embedlyClient: EmbedlyClient = new DevEmbedlyClient()

  @Provides
  @Singleton
  def pagePeekerClient: PagePeekerClient = new DevPagePeekerClient()
}
