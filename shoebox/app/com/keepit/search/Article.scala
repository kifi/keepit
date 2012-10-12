package com.keepit.search

import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id

case class Article(normalizedUriId: Id[NormalizedURI], title:String, content:String)
