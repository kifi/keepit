package com.keepit.cortex.features

import com.keepit.search.Article
import com.keepit.common.db.Id
import com.keepit.search.Lang
import com.keepit.model.NormalizedURI
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.model.NormalizedURIStates

trait URIFeatureTestHelper {

  val english = Lang("en")

  def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String, contentLang: Lang = english) = {
    Article(
      id = normalizedUriId,
      title = title,
      description = None,
      author = None,
      publishedAt = None,
      canonicalUrl = None,
      alternateUrls = Set.empty,
      keywords = None,
      media = None,
      content = content,
      scrapedAt = currentDateTime,
      httpContentType = Some("text/html"),
      httpOriginalContentCharset = Option("UTF-8"),
      state = NormalizedURIStates.ACTIVE,
      message = None,
      titleLang = None,
      contentLang = Some(contentLang))
  }

}
