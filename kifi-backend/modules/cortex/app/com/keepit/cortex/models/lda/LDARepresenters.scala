package com.keepit.cortex.models.lda

import com.keepit.cortex.features._
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.nlp.Stopwords
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.google.inject.Inject
import com.keepit.search.Lang

case class LDAWordRepresenter(val version: ModelVersion[DenseLDA], lda: DenseLDA) extends HashMapWordRepresenter[DenseLDA](lda.dimension, lda.mapper)

case class LDADocRepresenter @Inject() (wordRep: LDAWordRepresenter, stopwords: Stopwords) extends NaiveSumDocRepresenter(wordRep, Some(stopwords)) {
  override def normalize(vec: Array[Float]): Array[Float] = {
    val s = vec.sum
    vec.map { x => x / s }
  }
}

case class LDAURIRepresenter @Inject() (docRep: LDADocRepresenter, articleStore: ArticleStore) extends BaseURIFeatureRepresenter(docRep, articleStore) {

  override def isDefinedAt(article: Article): Boolean = article.contentLang == Some(Lang("en"))

  override def toDocument(article: Article): Document = {
    Document(article.content.toLowerCase.split(" ")) // TODO(yingjie): Lucene tokenize
  }
}
