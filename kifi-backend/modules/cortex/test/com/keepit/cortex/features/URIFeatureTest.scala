package com.keepit.cortex.features

import org.specs2.mutable.Specification
import com.keepit.model.NormalizedURI
import com.keepit.search.InMemoryArticleStoreImpl
import com.keepit.common.db.Id
import com.keepit.model.UrlHash
import com.keepit.search.Article
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.model.NormalizedURIStates
import com.keepit.search.Lang


class URIFeatureTest extends Specification with WordFeatureTestHelper {
  "uri feature representer " should {

    val english = Lang("en")

    def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String, contentLang: Lang = english) = {
      Article(
        id = normalizedUriId,
        title = title,
        description = None,
        canonicalUrl = None,
        alternateUrls = Set.empty,
        keywords = None,
        media = None,
        content = content,
        scrapedAt = currentDateTime,
        httpContentType = Some("text/html"),
        httpOriginalContentCharset = Option("UTF-8"),
        state = NormalizedURIStates.SCRAPED,
        message = None,
        titleLang = None,
        contentLang = Some(contentLang))
    }

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

      val uriRep = new BaseURIFeatureRepresenter[FakeWordModel](fakeDocRep, articleStore){
        protected def isDefinedAt(article: Article): Boolean = article.contentLang.isDefined && article.contentLang.get == Lang("en")
        protected def toDocument(article: Article): Document = Document(article.content.split(" "))
      }

      uriRep(uri1).get.vectorize === Array(1f, 0f)
      uriRep(uri2) === None   // non-english
      uriRep(uri3) === None   // junk words

    }
  }
}
