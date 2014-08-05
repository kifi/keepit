package com.keepit.scraper.embedly

case class FakeEmbedlyModule() extends EmbedlyModule {
  def configure(): Unit = {
    bind[EmbedlyClient].to[FakeEmbedlyClient]
  }
}
