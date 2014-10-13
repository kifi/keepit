package com.keepit.commanders

import net.codingwell.scalaguice.ScalaModule

case class FakeShoeboxCommandersModule() extends ScalaModule {

  def configure(): Unit = {
    bind[RecommendationsCommander].to[FakeRecommendationsCommander]
  }

}
