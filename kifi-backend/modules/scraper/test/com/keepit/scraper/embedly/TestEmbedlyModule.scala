package com.keepit.scraper.embedly

case class TestEmbedlyModule() extends EmbedlyModule {
  def configure(): Unit = {
    bind[EmbedlyClient].to[TestEmbedlyClient]
  }
}
