package com.keepit.search

import com.keepit.common.db.{Id, State}
import com.keepit.model.NormalizedURI
import org.joda.time.DateTime

case class Article(
    id: Id[NormalizedURI],
    title: String,
    content: String,
    description: Option[String],
    scrapedAt: DateTime,
    httpContentType: Option[String], // from http header
    httpOriginalContentCharset: Option[String], // from EntityUtils.getContentCharSet
    state: State[NormalizedURI],
    message: Option[String],
    titleLang: Option[Lang],
    contentLang: Option[Lang],
    destinationUrl: Option[String] = None)

