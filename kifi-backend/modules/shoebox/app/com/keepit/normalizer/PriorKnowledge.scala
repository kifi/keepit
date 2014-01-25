package com.keepit.normalizer

import com.keepit.model.UrlPatternRuleRepo
import com.keepit.scraper.{Signature, ScrapeSchedulerPlugin}
import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.DBSession.RSession

@Singleton
class PriorKnowledge @Inject() (urlPatternRuleRepo: UrlPatternRuleRepo, scraperPlugin: ScrapeSchedulerPlugin) {
  implicit val scraper = scraperPlugin

  def getContentChecks(referenceUrl: String, referenceSignature: Option[Signature] = None)(implicit session: RSession): Seq[ContentCheck] = {
    referenceUrl match {
      case LinkedInNormalizer.linkedInPrivateProfile(_, id) => Seq(LinkedInProfileCheck(id.toLong))
      case _ => Seq(SignatureCheck(referenceUrl, referenceSignature, urlPatternRuleRepo.getTrustedDomain(referenceUrl)))
    }
  }

  def getPreferredSchemeNormalizer(url: String)(implicit session: RSession): Option[StaticNormalizer] = urlPatternRuleRepo.getPreferredNormalization(url).map(SchemeNormalizer(_))

}


sealed trait ContentCheckTrustBehavior
case object WhiteList extends ContentCheckTrustBehavior
case object BlackList extends ContentCheckTrustBehavior
