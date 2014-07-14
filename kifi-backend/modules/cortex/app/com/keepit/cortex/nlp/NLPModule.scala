package com.keepit.cortex.nlp

import net.codingwell.scalaguice.ScalaModule

abstract class NLPModule(
    stopwords: StopwordsModule) extends ScalaModule {

  def configure {
    install(stopwords)
  }
}

case class NLPProdModule() extends NLPModule(
  stopwords = StopwordsModule(StopwordsProdStoreModule())
)

case class NLPDevModule() extends NLPModule(
  stopwords = StopwordsModule(StopwordsDevStoreModule())
)
