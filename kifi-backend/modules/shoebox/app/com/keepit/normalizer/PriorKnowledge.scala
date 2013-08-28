package com.keepit.normalizer

import scala.util.matching.Regex
import com.keepit.common.net.URI
import com.keepit.model.{NormalizedURIRepo, NormalizedURI}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.scraper.ScraperPlugin

case class PriorKnowledge(currentReference: NormalizedURI)(implicit normalizedURIRepo: NormalizedURIRepo, scraperPlugin: ScraperPlugin) {
  lazy val contentChecks = PriorKnowledge.getContentChecks(currentReference.url)

  def apply(candidate: NormalizationCandidate)(implicit session: RSession): PriorKnowledge.Action = candidate match {
    case _: TrustedCandidate => if (normalizedURIRepo.getByNormalizedUrl(candidate.url).isDefined) PriorKnowledge.ACCEPT else PriorKnowledge.REJECT // restrict renormalization to existing (hence valid) uris
    case _: UntrustedCandidate => contentChecks.find(_.isDefinedAt(candidate)).map(PriorKnowledge.Check).getOrElse(PriorKnowledge.REJECT)
  }
}

object PriorKnowledge {

  sealed trait Action
  case object ACCEPT extends Action
  case object REJECT extends Action
  case class Check(contentCheck: ContentCheck) extends Action

  val trustedDomains = Set.empty[String]
  val linkedInPrivateProfile = new Regex("""^https?://\w+\.linkedin\.com/profile/view\?id=(\d+)""", "id")
  val linkedInPublicProfile = new Regex("""^http://\w+\.linkedin\.com/(in/\w+|pub/[\P{M}\p{M}\w]+(/\w+){3})$""")

  def getContentChecks(referenceUrl: String)(implicit scraperPlugin: ScraperPlugin): Seq[ContentCheck] = {

    referenceUrl match {
      case linkedInPrivateProfile(id) => Seq(LinkedInProfileCheck(id.toLong))
      case linkedInPublicProfile(groups @ _*) => Seq(SignatureCheck(referenceUrl, "linkedin.com"))
      case _ => {
        val trustedDomain = getTrustedDomain(referenceUrl)
        if (trustedDomain.isDefined) Seq(SignatureCheck(referenceUrl, trustedDomain.get)) else Seq.empty
      }
    }
  }

  private def canBeTrusted(domain: String): Option[String] = trustedDomains.find(trustedDomain => domain.endsWith(trustedDomain))
  private def getDomain(referenceUrl: String): Option[String] = for { uri <- URI.safelyParse(referenceUrl); host <- uri.host } yield host.name
  private def getTrustedDomain(referenceUrl: String): Option[String] = for { domain <- getDomain(referenceUrl); trustedDomain <- canBeTrusted(domain) } yield trustedDomain
}
