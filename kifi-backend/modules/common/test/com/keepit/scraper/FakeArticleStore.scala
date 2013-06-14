package com.keepit.scraper

import com.keepit.search.ArticleStore
import scala.collection.mutable.HashMap
import com.keepit.search.Article
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI

class FakeArticleStore extends HashMap[Id[NormalizedURI], Article] with ArticleStore
