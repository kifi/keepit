package com.keepit.search

import com.keepit.common.db.{Id, State}
import com.keepit.model.NormalizedURI
import org.joda.time.DateTime

case class Article(
    id: Id[NormalizedURI],
    title: String,
    content: String,
    scrapedAt: DateTime,
    httpContentType: Option[String], // from http header
    state: State[NormalizedURI],
    message: Option[String])

