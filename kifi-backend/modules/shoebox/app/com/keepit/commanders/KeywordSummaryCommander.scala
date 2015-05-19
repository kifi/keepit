package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.cortex.CortexServiceClient
import com.keepit.cortex.models.word2vec.Word2VecKeywords
import com.keepit.model.NormalizedURI
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.EmbedlyArticle
import com.keepit.rover.article.content.EmbedlyKeyword

import scala.concurrent.{ ExecutionContext, Future }

case class KeywordsSummary(article: Seq[String], embedly: Seq[EmbedlyKeyword], word2vecCosine: Seq[String], word2vecFreq: Seq[String], word2vecWordCount: Int, bestGuess: Seq[String])

class KeywordSummaryCommander @Inject() (
    rover: RoverServiceClient,
    cortex: CortexServiceClient,
    implicit val executionContext: ExecutionContext) {

  def getFetchedKeywords(uriId: Id[NormalizedURI]): Future[(Set[String], Seq[EmbedlyKeyword])] = {
    rover.getBestArticlesByUris(Set(uriId)).map { articlesByUriIds =>
      val articles = articlesByUriIds(uriId)
      val embedlyKeywords = articles.collectFirst {
        case article: EmbedlyArticle => article.content.embedlyKeywords.sortBy(_.score * -1)
      } getOrElse Seq.empty
      val otherKeywords = articles.collect {
        case article if article.kind != EmbedlyArticle => article.content.keywords.collect {
          case keyword if keyword.forall(_.isLetterOrDigit) => keyword.toLowerCase
        }
      }.flatten.toSet
      (otherKeywords, embedlyKeywords)
    }
  }

  def getWord2VecKeywords(id: Id[NormalizedURI]): Future[Option[Word2VecKeywords]] = {
    cortex.word2vecURIKeywords(id)
  }

  def batchGetWord2VecKeywords(ids: Seq[Id[NormalizedURI]]): Future[Seq[Option[Word2VecKeywords]]] = {
    cortex.word2vecBatchURIKeywords(ids)
  }

  // FYI this is very slow. Be careful calling it. (Yingjie)
  def getKeywordsSummary(uri: Id[NormalizedURI]): Future[KeywordsSummary] = {
    val word2vecKeywordsFut = getWord2VecKeywords(uri)
    val fetchedKeywordsFut = getFetchedKeywords(uri)

    for {
      word2vecKeys <- word2vecKeywordsFut
      (articleKeywords, embedlyKeywords) <- fetchedKeywordsFut
    } yield {
      val word2vecCount = word2vecKeys.map { _.wordCounts } getOrElse 0
      val w2vCos = word2vecKeys.map { _.cosine.toSet } getOrElse Set()
      val w2vFreq = word2vecKeys.map { _.freq.toSet } getOrElse Set()
      val embedlyKeywordsStr = embedlyKeywords.map(_.name).toSet

      val bestGuess = if (!articleKeywords.isEmpty) {
        articleKeywords intersect (embedlyKeywordsStr union w2vCos union w2vFreq)
      } else {
        if (embedlyKeywords.isEmpty) {
          w2vCos intersect w2vFreq
        } else {
          embedlyKeywordsStr intersect (w2vCos union w2vFreq)
        }
      }

      KeywordsSummary(articleKeywords.toSeq, embedlyKeywords, w2vCos.toSeq, w2vFreq.toSeq, word2vecCount, bestGuess.toSeq)
    }

  }

}
