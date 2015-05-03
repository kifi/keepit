package com.keepit.cortex.models.lda

import com.keepit.cortex.article.{ CortexArticleProvider, CortexArticle }
import com.keepit.cortex.features._
import com.keepit.cortex.core.{ MultiVersionedFeatureRepresenter, ModelVersion }
import com.keepit.cortex.nlp.Stopwords
import com.keepit.cortex.utils.TextUtils
import com.keepit.search.Lang

case class LDAWordRepresenter(val version: ModelVersion[DenseLDA], lda: DenseLDA) extends HashMapWordRepresenter[DenseLDA](lda.dimension, lda.mapper)

case class LDADocRepresenter(wordRep: LDAWordRepresenter, stopwords: Stopwords) extends NaiveSumDocRepresenter(wordRep, Some(stopwords)) {
  override def normalize(vec: Array[Float]): Array[Float] = {
    val s = vec.sum
    vec.map { x => x / s }
  }
}

case class LDAURIRepresenter(docRep: LDADocRepresenter, articleProvider: CortexArticleProvider) extends BaseURIFeatureRepresenter(docRep, articleProvider) {

  override def isDefinedAt(article: CortexArticle): Boolean = article.contentLang == Some(Lang("en"))

  override def toDocument(article: CortexArticle): Document = {
    Document(TextUtils.TextTokenizer.LowerCaseTokenizer.tokenize(article.content))
  }
}

case class MultiVersionedLDAWordRepresenter(representers: LDAWordRepresenter*) extends MultiVersionedFeatureRepresenter[DenseLDA, LDAWordRepresenter](representers)

case class MultiVersionedLDADocRepresenter(representers: LDADocRepresenter*) extends MultiVersionedFeatureRepresenter[DenseLDA, LDADocRepresenter](representers)

case class MultiVersionedLDAURIRepresenter(representers: LDAURIRepresenter*) extends MultiVersionedFeatureRepresenter[DenseLDA, LDAURIRepresenter](representers)
