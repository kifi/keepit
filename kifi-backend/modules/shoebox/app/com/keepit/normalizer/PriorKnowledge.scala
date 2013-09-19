package com.keepit.normalizer

import com.keepit.model.UrlPatternRuleRepo
import com.keepit.scraper.ScraperPlugin
import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.DBSession.RSession

@Singleton
class PriorKnowledge @Inject() (urlPatternRuleRepo: UrlPatternRuleRepo, scraperPlugin: ScraperPlugin) {
  implicit val scraper = scraperPlugin

  def getContentChecks(referenceUrl: String)(implicit session: RSession): Seq[ContentCheck] = {

    referenceUrl match {
      case LinkedInNormalizer.linkedInPrivateProfile(_, id) => Seq(LinkedInProfileCheck(id.toLong))
      case _ => {
        val trustedDomain = urlPatternRuleRepo.getTrustedDomain(referenceUrl)
        if (trustedDomain.isDefined) Seq(SignatureCheck(referenceUrl, trustedDomain.get)) else Seq.empty
      }
    }
  }

  def getPreferredSchemeNormalizer(url: String)(implicit session: RSession): Option[StaticNormalizer] = urlPatternRuleRepo.getPreferredNormalization(url).map(SchemeNormalizer(_))

}


