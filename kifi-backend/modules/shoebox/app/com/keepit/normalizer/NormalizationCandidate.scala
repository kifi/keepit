package com.keepit.normalizer

import com.keepit.model.{ NormalizedURI, RawKeep, Normalization }
import com.keepit.common.db.Id
import com.keepit.commanders.RawBookmarkRepresentation
import com.keepit.rover.article.Signature
import play.api.libs.json._
import play.api.libs.functional.syntax._

sealed trait NormalizationCandidate {
  val candidateType: String
  val url: String
  val normalization: Normalization
  def isTrusted: Boolean
}

case class VerifiedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  val candidateType = "verified"
  def isTrusted = true
}

case class ScrapedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  val candidateType = "scraped"
  def isTrusted = true
}

case class AlternateCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  val candidateType = "alternate"
  def isTrusted = false
}

case class UntrustedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  val candidateType = "untrusted"
  def isTrusted = false
}

object NormalizationCandidate {

  val acceptedSubmissions = Seq(Normalization.CANONICAL, Normalization.OPENGRAPH)

  // todo(ray/leo): avoid overloading apply()

  def apply(t: String, url: String, n: Normalization, isTrusted: Boolean): NormalizationCandidate = {
    t match {
      case "verified" => VerifiedCandidate(url, n)
      case "scraped" => ScrapedCandidate(url, n)
      case "alternate" => AlternateCandidate(url, n)
      case "untrusted" => UntrustedCandidate(url, n)
    }
  }

  def unapply(nc: NormalizationCandidate) = Some((nc.candidateType, nc.url, nc.normalization, nc.isTrusted))

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

  implicit val format = (
    (__ \ 'candidateType).format[String] and
    (__ \ 'url).format[String] and
    (__ \ 'normalization).format[Normalization] and
    (__ \ 'isTrusted).format[Boolean]
  )(NormalizationCandidate.apply(_: String, _: String, _: Normalization, _: Boolean), unlift(NormalizationCandidate.unapply))

}

case class NormalizationReference(uri: NormalizedURI, isNew: Boolean = false, correctedNormalization: Option[Normalization] = None, signature: Option[Signature] = None) {
  require(uri.id.isDefined, "NormalizedURI must be persisted before it can be considered a reference normalization")
  override def toString(): String = s"NormalizationReference(id=${uriId}, url=${url}, isNew=$isNew, persistedNorm=${persistedNormalization}, correctedNorm=${normalization})"
  def uriId: Id[NormalizedURI] = uri.id.get
  def url = uri.url
  def persistedNormalization: Option[Normalization] = uri.normalization
  def normalization: Option[Normalization] = correctedNormalization orElse persistedNormalization
}

