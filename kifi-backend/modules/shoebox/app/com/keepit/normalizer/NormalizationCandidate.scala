package com.keepit.normalizer

import com.keepit.model.{NormalizedURI, RawKeep, Normalization}
import play.api.libs.json.JsObject
import com.keepit.commanders.RawBookmarkRepresentation
import com.keepit.scraper.Signature
import com.keepit.common.db.Id

sealed trait NormalizationCandidate {
  val url: String
  val normalization: Normalization
  def isTrusted: Boolean
}

case class VerifiedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  def isTrusted = true
}

case class ScrapedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  def isTrusted = true
}

case class UntrustedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  def isTrusted = false
}

object NormalizationCandidate {

  val acceptedSubmissions = Seq(Normalization.CANONICAL, Normalization.OPENGRAPH)

  def apply(json: JsObject): Seq[UntrustedCandidate] = {
    for {
      normalization <- acceptedSubmissions
      url <- (json \ normalization.scheme).asOpt[String]
    } yield UntrustedCandidate(url, normalization)
  }

  def apply(rawBookmark: RawBookmarkRepresentation): Seq[UntrustedCandidate] = {
    (rawBookmark.canonical.map(UntrustedCandidate(_, Normalization.CANONICAL)) :: rawBookmark.openGraph.map(UntrustedCandidate(_, Normalization.OPENGRAPH)) :: Nil).flatten
  }

  def apply(rawKeep: RawKeep): Seq[UntrustedCandidate] = {
    rawKeep.originalJson.map { json =>
      val canonical = (json \ Normalization.CANONICAL.scheme).asOpt[String]
      val openGraph = (json \ Normalization.OPENGRAPH.scheme).asOpt[String]
      (canonical.map(UntrustedCandidate(_, Normalization.CANONICAL)) :: openGraph.map(UntrustedCandidate(_, Normalization.OPENGRAPH)) :: Nil).flatten
    }.getOrElse(Nil)
  }
}

case class NormalizationReference(uri: NormalizedURI, isNew: Boolean = false, signature: Option[Signature] = None) {
  require(uri.id.isDefined, "NormalizedURI must be persisted before it can be considered a reference normalization")
  def uriId: Id[NormalizedURI] = uri.id.get
  def url = uri.url
  def normalization: Option[Normalization] = uri.normalization
}

