package com.keepit.rover.model

import com.keepit.rover.article.DefaultArticle
import com.keepit.rover.test.{ RoverApplication, RoverApplicationInjector }
import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import play.api.test.Helpers._

class RoverRepoTest extends Specification with RoverApplicationInjector {
  "Rover" should {
    "save and retrieve models" in {
      running(new RoverApplication()) {

        // ArticleRepo
        val articleInfoRepo = inject[ArticleInfoRepo]
        db.readWrite { implicit session =>
          val saved = articleInfoRepo.save(ArticleInfo(uriId = Id(1), url = "http://www.lemonde.fr", kind = DefaultArticle.typeCode))
          articleInfoRepo.get(saved.id.get).uriId === Id(1)
        }

        // HttpProxyRepo
        val httpProxyRepo = inject[RoverHttpProxyRepo]
        db.readWrite { implicit session =>
          val saved = httpProxyRepo.save(RoverHttpProxy(alias = "marvin", host = "96.31.86.149", port = 3128, scheme = "http", username = None, password = None))
          httpProxyRepo.get(saved.id.get).alias === "marvin"
        }
      }
    }
  }
}
