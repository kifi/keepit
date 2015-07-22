package com.keepit.model

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database

class UrlPatternRulesCommander @Inject() (
    db: Database,
    urlPatternRuleRepo: UrlPatternRuleRepo) {

  def rules(): UrlPatternRules = db.readOnlyMaster { implicit s =>
    urlPatternRuleRepo.getUrlPatternRules()
  }

}
