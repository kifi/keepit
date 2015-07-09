package com.keepit.rover.model

import com.keepit.common.db.slick.Database
import com.keepit.rover.rule.UrlRuleAction
import com.keepit.rover.test.{ RoverTestInjector }
import org.specs2.mutable.Specification

class RoverUrlRuleTest extends Specification with RoverTestInjector {

  "RoverUrlRule" should {

    "persist & use patterns" in {
      withDb() { implicit injector =>

        val db = inject[Database]

        val httpProxyRepo = inject[RoverHttpProxyRepo]
        val urlRuleRepo = inject[RoverUrlRuleRepo]

        val proxyId =
          db.readWrite { implicit session =>
            httpProxyRepo.save(RoverHttpProxy(alias = "proxy1", host = "localhost", port = 8080, scheme = ProxyScheme.Http, username = None, password = None)).id
          }

        db.readWrite { implicit session =>
          urlRuleRepo.save(RoverUrlRule(pattern = "^https*://www\\.facebook\\.com/login.*$", proxy = proxyId))
          urlRuleRepo.save(RoverUrlRule(pattern = "^https*://.*.google.com.*/ServiceLogin.*$", proxy = None))
          urlRuleRepo.save(RoverUrlRule(pattern = "^https*://app.asana.com.*$", proxy = None))
        }

        db.readOnlyMaster { implicit session =>
          urlRuleRepo.actionsFor("http://www.google.com/") === List()
          urlRuleRepo.actionsFor("https://www.facebook.com/login.php?bb") === List(UrlRuleAction.UseProxy(proxyId.get))
        }
      }
    }

  }

}
