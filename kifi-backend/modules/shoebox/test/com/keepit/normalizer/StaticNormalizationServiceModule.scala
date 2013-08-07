package com.keepit.normalizer

import net.codingwell.scalaguice.ScalaModule

case class StaticNormalizationServiceModule() extends ScalaModule {
  def configure() = {
    bind[NormalizationService].toInstance(StaticNormalizationService)
  }
}
