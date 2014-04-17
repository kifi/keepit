package com.keepit.cortex

import scala.concurrent.Future

class FakeCortexServiceClientImpl extends CortexServiceClientImpl(null, null, null){
  override def word2vecWordSimilarity(word1: String, word2: String): Future[Option[Float]] = ???
  override def word2vecKeywordsAndBOW(text: String): Future[Map[String, String]] = ???
}
