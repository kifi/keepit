package com.keepit.scraper.mediatypes

import com.keepit.scraper.extractor.Extractor

object MediaTypes {

  def apply(x: Extractor): MediaTypes = {
    x.getMetadata("Content-Type") match {
      case Some(httpContentType) => if (httpContentType startsWith "text/html") OpenGraph else InternetMediaTypes
      case _ => InternetMediaTypes

    }
  }

}

trait MediaTypes {

  val typeMetadata: String
  def toMediaTypeString(t: String): Option[String]

  def getMediaTypeString(x: Extractor): Option[String] = {
    // extract object type
    x.getMetadata(typeMetadata).flatMap(toMediaTypeString(_))
  }
}