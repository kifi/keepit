package com.keepit.rover.model

import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.rover.rule.{ RoverHttpProxyCommander, RoverUrlRuleCommander, UrlRuleAction }
import com.keepit.rover.test.{ RoverTestInjector }
import org.specs2.mutable.Specification

class RoverUrlRuleTest extends Specification with RoverTestInjector {

  "RoverUrlRule" should {

    val proxy1 = RoverHttpProxy(alias = "proxy1", host = "localhost", port = 8080, scheme = ProxyScheme.Http, username = None, password = None)

    "persist & use patterns" in {
      withDb() { implicit injector =>

        val db = inject[Database]

        val httpProxyRepo = inject[RoverHttpProxyRepo]
        val urlRuleRepo = inject[RoverUrlRuleRepo]

        val proxyId =
          db.readWrite { implicit session =>
            httpProxyRepo.save(proxy1).id
          }

        db.readWrite { implicit session =>
          urlRuleRepo.save(RoverUrlRule(pattern = "^https*://www\\.facebook\\.com/login.*$", example = "", proxy = proxyId))
          urlRuleRepo.save(RoverUrlRule(pattern = "^https*://.*.google.com.*/ServiceLogin.*$", example = "", proxy = None))
          urlRuleRepo.save(RoverUrlRule(pattern = "^https*://app.asana.com.*$", example = "", proxy = proxyId))
        }

        db.readOnlyMaster { implicit session =>
          urlRuleRepo.actionsFor("http://www.google.com/") === List()
          urlRuleRepo.actionsFor("https://www.facebook.com/login.php?bb") === List(UrlRuleAction.UseProxy(proxyId.get))
        }
      }
    }

    "locate proxies for usage" in {
      withDb() { implicit injector =>

        val httpProxyCommander = inject[RoverHttpProxyCommander]
        val httpProxyRepo = inject[RoverHttpProxyRepo]
        val urlRuleCommander = inject[RoverUrlRuleCommander]
        val urlRuleRepo = inject[RoverUrlRuleRepo]

        val proxy = db.readWrite { implicit session =>
          val proxy = httpProxyRepo.save(proxy1)
          urlRuleRepo.save(RoverUrlRule(pattern = "^https*://app.asana.com.*$", example = "", proxy = proxy.id))
          proxy
        }

        val lightweightProxy = httpProxyCommander.roverHttpProxyToHttpProxy(proxy)

        db.readOnlyMaster { implicit session =>
          urlRuleCommander.lightweightProxyFor("http://www.google.com/") === None
          urlRuleCommander.lightweightProxyFor("https://app.asana.com/") === Some(lightweightProxy)
        }
      }
    }

  }

}
