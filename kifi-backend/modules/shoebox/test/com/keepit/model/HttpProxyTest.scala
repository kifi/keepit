package com.keepit.model

import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.db.slick._

class HttpProxyTest extends Specification with ShoeboxTestInjector {

  "Unscrapable" should {

    "persist & use http proxys w/ appropriate caching" in {
      withDb() { implicit injector =>

        val httpProxyCache = inject[HttpProxyRepoImpl].httpProxyAllCache

        httpProxyCache.get(HttpProxyAllKey()).isDefined === false

        db.readWrite { implicit session =>
          httpProxyRepo.save(HttpProxy(alias = "xxx", hostname = "x.x.x.x", scheme = "http", port = 80, username = Some("FortyTwo"), password = Some("ChangeMe")))
        }

        httpProxyCache.get(HttpProxyAllKey()).isDefined === false

        db.readOnlyMaster { implicit session => httpProxyRepo.allActive().length === 1 }
        httpProxyCache.get(HttpProxyAllKey()).isDefined
        httpProxyCache.get(HttpProxyAllKey()).get.length === 1

        db.readWrite { implicit session => httpProxyRepo.save(HttpProxy(alias = "yyy", hostname = "y.y.y.y", scheme = "http", port = 80, username = Some("FortyTwo"), password = Some("ChangeMe"))) }

        httpProxyCache.get(HttpProxyAllKey()).isDefined === false
      }
    }
  }
}
