package com.keepit.scraper

import com.keepit.model.NormalizedURI

case class ScraperError(val normalizedUri: NormalizedURI, val httpStatusCode: Int, msg: String)
