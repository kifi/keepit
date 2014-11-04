package com.keepit.cortex.models.lda

import com.keepit.cortex.features._
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.nlp.Stopwords
import com.keepit.cortex.utils.TextUtils
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.google.inject.Inject
import com.keepit.search.Lang

case class LDAWordRepresenter(val version: ModelVersion[DenseLDA], lda: DenseLDA) extends HashMapWordRepresenter[DenseLDA](lda.dimension, lda.mapper)

case class LDADocRepresenter(wordRep: LDAWordRepresenter, stopwords: Stopwords) extends NaiveSumDocRepresenter(wordRep, Some(stopwords)) {
  override def normalize(vec: Array[Float]): Array[Float] = {
    val s = vec.sum
    vec.map { x => x / s }
  }
}

case class LDAURIRepresenter(docRep: LDADocRepresenter, articleStore: ArticleStore) extends BaseURIFeatureRepresenter(docRep, articleStore) {

  override def isDefinedAt(article: Article): Boolean = article.contentLang == Some(Lang("en"))

  override def toDocument(article: Article): Document = {
    Document(TextUtils.TextTokenizer.LowerCaseTokenizer.tokenize(article.content))
  }
}

case class MultiVersionedLDAURIRepresenter(representers: LDAURIRepresenter*) {
  private val dimMap = representers.map { rep => (rep.version, rep.dimension) }.toMap
  def versions: Seq[ModelVersion[DenseLDA]] = representers.map { _.version }
  def getRepresenter(version: ModelVersion[DenseLDA]): Option[LDAURIRepresenter] = representers.find(_.version == version)
  def getDimension(version: ModelVersion[DenseLDA]): Option[Int] = dimMap.get(version)
}
