package com.keepit.model

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database

class UrlPatternRulesCommander @Inject() (
    db: Database,
    httpProxyRepo: HttpProxyRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo) {

  def rules(): UrlPatternRules = db.readOnlyMaster { implicit s =>
    urlPatternRuleRepo.getUrlPatternRules()
  }

  def getProxy(url: String)(implicit session: RSession): Option[HttpProxy] = {
    val allRules = rules()
    for {
      rule <- allRules.findFirst(url)
      proxyId <- rule.useProxy
      proxy <- db.readOnlyMaster { implicit s => httpProxyRepo.allActive().find(_.id == Some(proxyId)) }
    } yield proxy
  }
}
