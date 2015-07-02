package com.keepit.cortex.models.word2vec

import com.google.inject.Inject
import com.keepit.common.akka.MonitoredAwait
import com.keepit.cortex.features._
import com.keepit.cortex.core._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.content.ArticleContentExtractor
import com.keepit.search.Lang
import com.keepit.cortex.utils.TextUtils.TextTokenizer
import com.keepit.model.NormalizedURI
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.Future

case class Word2VecWordRepresenter(val version: ModelVersion[Word2Vec], word2vec: Word2Vec) extends HashMapWordRepresenter[Word2Vec](word2vec.dimension, word2vec.mapper)

case class RichWord2VecURIRepresenter @Inject() (
    word2vec: Word2VecWordRepresenter,
    rover: RoverServiceClient,
    monitoredAwait: MonitoredAwait) extends URIFeatureRepresenter[Word2Vec, RichWord2VecURIFeature] {

  override val version = word2vec.version
  override val dimension = word2vec.dimension
  private val doc2vec = new Doc2Vec(word2vec.mapper, word2vec.dimension)

  private def isDefinedAt(article: ArticleContentExtractor): Boolean = article.contentLang == Some(Lang("en"))
  private def toDocument(article: ArticleContentExtractor) = Document(TextTokenizer.LowerCaseTokenizer.tokenize(article.content.getOrElse("")))
  private def getFutureArticle(uri: NormalizedURI): Future[Option[ArticleContentExtractor]] = {
    rover.getBestArticlesByUris(Set(uri.id.get)).imap { articlesById =>
      articlesById.get(uri.id.get).collect {
        case articles if articles.nonEmpty =>
          new ArticleContentExtractor(articles)
      }
    }
  }

  private def getArticle(uri: NormalizedURI): Option[ArticleContentExtractor] = {
    monitoredAwait.result(getFutureArticle(uri), 10 seconds, s"Get content from Rover for uri: $uri")
  }

  override def genFeatureAndWordCount(uri: NormalizedURI): (Option[RichWord2VecURIFeature], Int) = {
    getArticle(uri) match {
      case None => (None, 0)
      case Some(article) =>
        if (isDefinedAt(article)) {
          val doc = toDocument(article)
          val sampleOpt = doc2vec.sampleBest(doc.tokens, numTry = 5)
          val featOpt = sampleOpt.map { res => RichWord2VecURIFeature(dimension, res.vec, res.keywords, res.bagOfWords) }
          val cnt = sampleOpt.map { _.bagOfWords.size } getOrElse 0
          (featOpt, cnt)
        } else (None, 0)
    }
  }
}
