package com.keepit.normalizer

import com.keepit.model.UrlPatternRuleRepo
import com.keepit.scraper.{Signature, ScrapeSchedulerPlugin}
import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.DBSession.RSession

@Singleton
class PriorKnowledge @Inject() (urlPatternRuleRepo: UrlPatternRuleRepo, scraperPlugin: ScrapeSchedulerPlugin) {
  implicit val scraper = scraperPlugin

  def getContentChecks(referenceUrl: String, referenceSignature: Option[Signature] = None): Seq[ContentCheck] = {
    referenceUrl match {
      case LinkedInNormalizer.linkedInPrivateProfile(_, id) => Seq(LinkedInProfileCheck(id.toLong))
      case _ => Seq(SignatureCheck(referenceUrl, referenceSignature, urlPatternRuleRepo.rules().getTrustedDomain(referenceUrl)))
    }
  }

  def getPreferredSchemeNormalizer(url: String): Option[StaticNormalizer] = urlPatternRuleRepo.rules().getPreferredNormalization(url).map(SchemeNormalizer(_))

}
