package com.keepit.cortex.models.word2vec

import com.google.inject.Inject
import com.keepit.cortex.features._
import com.keepit.cortex.core._
import com.keepit.search.ArticleStore
import com.keepit.search.Article
import com.keepit.search.Lang
import com.keepit.cortex.utils.TextUtils.TextTokenizer
import com.keepit.model.NormalizedURI

case class Word2VecWordRepresenter(val version: ModelVersion[Word2Vec], word2vec: Word2Vec) extends HashMapWordRepresenter[Word2Vec](word2vec.dimension, word2vec.mapper)

case class Word2VecDocRepresenter @Inject()(
  word2vec: Word2VecWordRepresenter
) extends DocRepresenter[Word2Vec, FloatVecFeature[Document, Word2Vec]]{

  val dimension = word2vec.dimension
  val version = word2vec.version

  private val doc2vec = new Doc2Vec(word2vec.mapper, word2vec.dimension)

  override def apply(doc: Document): Option[FloatVecFeature[Document, Word2Vec]] = {
    doc2vec.sampleBest(doc.tokens, numTry = 4).map{ res =>
      FloatVecFeature[Document, Word2Vec](res.vec)
    }
  }
}

case class Word2VecURIRepresenter @Inject()(
  docRep: Word2VecDocRepresenter,
  articleStore: ArticleStore
) extends BaseURIFeatureRepresenter(docRep, articleStore) {

  override def isDefinedAt(article: Article): Boolean = article.contentLang == Some(Lang("en"))

  override def toDocument(article: Article): Document = {
    Document(TextTokenizer.LowerCaseTokenizer.tokenize(article.content))    // TODO(yingjie): Lucene tokenize
  }
}

case class RichWord2VecURIRepresenter @Inject()(
  word2vec: Word2VecWordRepresenter,
  articleStore: ArticleStore
) extends URIFeatureRepresenter[Word2Vec, RichWord2VecURIFeature]{

  override val version = word2vec.version
  override val dimension = word2vec.dimension
  private val doc2vec = new Doc2Vec(word2vec.mapper, word2vec.dimension)

  private def isDefinedAt(article: Article): Boolean = article.contentLang == Some(Lang("en"))
  private def toDocument(article: Article) = Document(TextTokenizer.LowerCaseTokenizer.tokenize(article.content))
  private def getArticle(uri: NormalizedURI): Option[Article] = articleStore.get(uri.id.get)

  override def apply(uri: NormalizedURI): Option[RichWord2VecURIFeature] = {
    getArticle(uri) match {
      case None => None
      case Some(article) =>
        if (isDefinedAt(article)){
          val doc = toDocument(article)
          doc2vec.sampleBest(doc.tokens, numTry = 6).map{ res =>
            RichWord2VecURIFeature(dimension, res.vec, res.keywords, res.bagOfWords)
          }
        } else None
    }
  }
}
