package com.keepit.cortex.features

import com.keepit.cortex.article.{ FakeCortexArticleProvider, CortexArticle }
import org.specs2.mutable.Specification

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.model.UrlHash
import com.keepit.search.Lang

class URIFeatureTest extends Specification with WordFeatureTestHelper {
  "uri feature representer " should {

    "work" in {
      val articleProvider = new FakeCortexArticleProvider()

      val uri1 = NormalizedURI(id = Some(Id[NormalizedURI](1)), url = "http://intel.com", urlHash = UrlHash("intel"))
      val a1 = articleProvider.setArticle(uri1.id.get, "intel and amd are bros")

      val uri2 = NormalizedURI(id = Some(Id[NormalizedURI](2)), url = "http://baidu.com", urlHash = UrlHash("baidu"))
      val a2 = articleProvider.setArticle(uri2.id.get, "东风夜放花千树", Lang("zh"))

      val uri3 = NormalizedURI(id = Some(Id[NormalizedURI](3)), url = "http://random.com", urlHash = UrlHash("random"))
      val a3 = articleProvider.setArticle(uri3.id.get, "blah blah blah ...")

      val uriRep = new BaseURIFeatureRepresenter[FakeWordModel](fakeDocRep, articleProvider) {
        protected def isDefinedAt(article: CortexArticle): Boolean = article.contentLang.isDefined && article.contentLang.get == Lang("en")
        protected def toDocument(article: CortexArticle): Document = Document(article.content.split(" "))
      }

      uriRep(uri1).get.vectorize === Array(1f, 0f)
      uriRep(uri2) === None // non-english
      uriRep(uri3) === None // junk words

    }
  }
}
