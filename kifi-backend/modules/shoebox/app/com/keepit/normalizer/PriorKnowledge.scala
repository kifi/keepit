package com.keepit.normalizer

import com.keepit.common.net.URI
import com.keepit.model.NormalizedURI
import com.keepit.scraper.ScraperPlugin
import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.classify.DomainRepo

@Singleton
class PriorKnowledge @Inject() (db: Database, domainRepo: DomainRepo) {

  private def canBeTrusted(domain: String): Option[String] = None // To be implemented (for a given domain, DomainRepo would have to store a trusted domain (None, domain itself or a superset of domain)
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


