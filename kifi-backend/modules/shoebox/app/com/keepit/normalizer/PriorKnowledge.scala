package com.keepit.normalizer

import com.keepit.common.net.URI
import com.keepit.model.{NormalizedURIRepo, NormalizedURI}
import com.keepit.scraper.ScraperPlugin

case class PriorKnowledge(currentReference: NormalizedURI)(implicit normalizedURIRepo: NormalizedURIRepo, scraperPlugin: ScraperPlugin) {
  lazy val contentChecks = PriorKnowledge.getContentChecks(currentReference.url)

  def apply(candidate: NormalizationCandidate): PriorKnowledge.Action = candidate match {
    case candidate if !PriorKnowledge.getDomain(candidate.url).map(domain => domain.endsWith("facebook.com") || domain.endsWith("linkedin.com") || domain.endsWith("kifi.com")).getOrElse(false) => PriorKnowledge.REJECT
    case _: TrustedCandidate => PriorKnowledge.ACCEPT
    case _: UntrustedCandidate => contentChecks.find(_.isDefinedAt(candidate)).map(PriorKnowledge.Check).getOrElse(PriorKnowledge.REJECT)
  }
}

object PriorKnowledge {

  sealed trait Action
  case object ACCEPT extends Action
  case object REJECT extends Action
  case class Check(contentCheck: ContentCheck) extends Action

  val trustedDomains = Set.empty[String]
  private def canBeTrusted(domain: String): Option[String] = trustedDomains.find(trustedDomain => domain.endsWith(trustedDomain))
  private def getDomain(referenceUrl: String): Option[String] = for { uri <- URI.safelyParse(referenceUrl); host <- uri.host } yield host.name
  private def getTrustedDomain(referenceUrl: String): Option[String] = for { domain <- getDomain(referenceUrl); trustedDomain <- canBeTrusted(domain) } yield trustedDomain

  def getContentChecks(referenceUrl: String)(implicit scraperPlugin: ScraperPlugin): Seq[ContentCheck] = {

    referenceUrl match {
      case LinkedInNormalizer.linkedInPrivateProfile(_, id) => Seq(LinkedInProfileCheck(id.toLong))
      case LinkedInNormalizer.linkedInPublicProfile(_, _) => Seq(SignatureCheck(referenceUrl, "linkedin.com"))
      case _ => {
        val trustedDomain = getTrustedDomain(referenceUrl)
        if (trustedDomain.isDefined) Seq(SignatureCheck(referenceUrl, trustedDomain.get)) else Seq.empty
      }
    }
  }

}
