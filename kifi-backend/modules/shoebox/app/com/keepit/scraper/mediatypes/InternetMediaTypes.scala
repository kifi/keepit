package com.keepit.scraper.mediatypes

object InternetMediaTypes extends MediaTypes {

  val typeMetadata = "Content-Type"

  // object types to string
  private[this] val objTypeMap: Map[String, Option[String]] = Map(
    "application/pdf" -> Some("pdf")

  )

  def toMediaTypeString(t: String): Option[String] = objTypeMap.getOrElse(t, None)
}
