package com.keepit.model

import com.keepit.common.db.{ExternalId, ModelWithExternalId, Id}
import org.joda.time.DateTime
import com.keepit.common.time._

case class URIImageSource(source: String)
object URIImageSource {
  val EMBEDLY = URIImageSource("embedly")
  val PAGEPEEKER = URIImageSource("pagepeeker")
}

case class URIImageFormat(format: String)
object URIImageFormat {
  val JPG = URIImageFormat("jpg")
  val PNG = URIImageFormat("png")
}

case class URIImage(
  id: Option[Id[URIImage]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[URIImage] = ExternalId(),
  width: Int,
  height: Int,
  source: URIImageSource,
  format: URIImageFormat,
  sourceUrl: String
  ) extends ModelWithExternalId[URIImage] {
  def withId(id: Id[URIImage]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}
