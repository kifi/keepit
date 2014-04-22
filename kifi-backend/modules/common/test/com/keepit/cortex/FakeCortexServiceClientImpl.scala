package com.keepit.cortex

import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI

class FakeCortexServiceClientImpl extends CortexServiceClientImpl(null, null, null){
  override def word2vecWordSimilarity(word1: String, word2: String): Future[Option[Float]] = ???
  override def word2vecKeywordsAndBOW(text: String): Future[Map[String, String]] = ???
  override def word2vecURISimilairty(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]): Future[Option[Float]] = ???
  override def word2vecUserSimilarity(user1Keeps: Seq[Id[NormalizedURI]], user2Keeps: Seq[Id[NormalizedURI]]): Future[Option[Float]] = ???
}
