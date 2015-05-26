package com.keepit.cortex.features

import com.keepit.cortex.article.{ StoreBasedArticleProvider, CortexArticle }
import org.specs2.mutable.Specification

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.model.UrlHash
import com.keepit.search.Article
import com.keepit.search.InMemoryArticleStoreImpl
import com.keepit.search.Lang

class URIFeatureTest extends Specification with WordFeatureTestHelper with URIFeatureTestHelper {
  "uri feature representer " should {

    "work" in {
      val articleStore = new InMemoryArticleStoreImpl()

      val uri1 = NormalizedURI(id = Some(Id[NormalizedURI](1)), url = "http://intel.com", urlHash = UrlHash("intel"))
      val a1 = mkArticle(uri1.id.get, title = "intel", content = "intel and amd are bros")

      val uri2 = NormalizedURI(id = Some(Id[NormalizedURI](2)), url = "http://baidu.com", urlHash = UrlHash("baidu"))
      val a2 = mkArticle(uri1.id.get, title = "baidu", content = "东风夜放花千树", contentLang = Lang("zh"))

      val uri3 = NormalizedURI(id = Some(Id[NormalizedURI](3)), url = "http://random.com", urlHash = UrlHash("random"))
      val a3 = mkArticle(uri1.id.get, title = "random", content = "blah blah blah ...", contentLang = english)

      articleStore.+=(uri1.id.get, a1)
      articleStore.+=(uri2.id.get, a2)
      articleStore.+=(uri3.id.get, a3)

      val uriRep = new BaseURIFeatureRepresenter[FakeWordModel](fakeDocRep, new StoreBasedArticleProvider(articleStore)) {
        protected def isDefinedAt(article: CortexArticle): Boolean = article.contentLang.isDefined && article.contentLang.get == Lang("en")
        protected def toDocument(article: CortexArticle): Document = Document(article.content.split(" "))
      }

      uriRep(uri1).get.vectorize === Array(1f, 0f)
      uriRep(uri2) === None // non-english
      uriRep(uri3) === None // junk words

    }
  }
}
