package com.keepit.cortex.features

import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.core.StatModel
import com.keepit.model.NormalizedURI
import com.keepit.search.ArticleStore
import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.search.Article
import com.keepit.cortex.core.FloatVecFeature

trait URIFeatureRepresenter[M <: StatModel, +FT <: FeatureRepresentation[NormalizedURI, M]] extends FeatureRepresenter[NormalizedURI, M, FT]

abstract class BaseURIFeatureRepresenter[M <: StatModel](
    docRepresenter: DocRepresenter[M, FeatureRepresentation[Document, M]],
    articleStore: ArticleStore) extends URIFeatureRepresenter[M, FeatureRepresentation[NormalizedURI, M]] {

  override val version = docRepresenter.version
  override val dimension = docRepresenter.dimension

  protected def isDefinedAt(article: Article): Boolean
  protected def toDocument(article: Article): Document

  private def getArticle(uri: NormalizedURI): Option[Article] = articleStore.get(uri.id.get)

  override def apply(uri: NormalizedURI): Option[FeatureRepresentation[NormalizedURI, M]] = {
    getArticle(uri) match {
      case None => None
      case Some(article) =>
        if (isDefinedAt(article)) {
          val doc = toDocument(article)
          val rep = docRepresenter.apply(doc)
          rep.map { x => FloatVecFeature[NormalizedURI, M](x.vectorize) }
        } else None
    }
  }

}
