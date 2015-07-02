package com.keepit.cortex.article

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.search.Lang

class FakeCortexArticleProvider extends CortexArticleProvider {
  private var articles = Map[Id[NormalizedURI], CortexArticle]()
  def setArticle(uriId: Id[NormalizedURI], article: CortexArticle): Unit = articles += (uriId -> article)
  def setArticle(uriId: Id[NormalizedURI], content: String, lang: Lang = Lang("en")): Unit = setArticle(uriId, BasicCortexArticle(Some(lang), content))
  def getArticle(uriId: Id[NormalizedURI]): Option[CortexArticle] = articles.get(uriId)
}
