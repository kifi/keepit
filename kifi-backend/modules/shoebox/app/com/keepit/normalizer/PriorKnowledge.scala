package com.keepit.normalizer

import com.keepit.common.net.URI
import com.keepit.model.Normalization
import com.keepit.scraper.ScraperPlugin
import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.classify.{Domain, DomainRepo}

@Singleton
class PriorKnowledge @Inject() (db: Database, domainRepo: DomainRepo) {

  private def trustedClosure(domain: Domain): Option[String] = None // To be implemented (for a given domain, DomainRepo would have to store a trusted domain (None, domain itself or a superset of domain)
  private def getDomain(referenceUrl: String): Option[Domain] = for { uri <- URI.safelyParse(referenceUrl); host <- uri.host; domain <- db.readOnly { implicit s => domainRepo.get(host.name) } } yield domain
  private def getTrustedDomain(referenceUrl: String): Option[String] = for { domain <- getDomain(referenceUrl); trustedDomain <- trustedClosure(domain) } yield trustedDomain

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

  def getPreferredSchemeNormalizer(url: String): Option[StaticNormalizer] = for { domain <- getDomain(url); normalization <- domain.normalizationScheme if Normalization.schemes.contains(normalization) } yield SchemeNormalizer(normalization)

}


