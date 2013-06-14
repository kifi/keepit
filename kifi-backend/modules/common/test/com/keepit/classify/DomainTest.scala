package com.keepit.classify


import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.inject.inject
import com.keepit.test.DbRepos
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test.Helpers._


class DomainTest extends Specification with DbRepos {
  "The domain repo" should {
    "save and retrieve domains by name and id" in {
      running(new EmptyApplication()) {
        val domainRepo = inject[DomainRepo]

        val d1 = Domain(hostname = "google.com", autoSensitive = Some(false))
        val d2 = Domain(hostname = "facebook.com", autoSensitive = Some(false))
        val d3 = Domain(hostname = "yahoo.com", autoSensitive = Some(false))

        inject[Database].readWrite { implicit c =>
          val Seq(sd1, sd2, sd3) = Seq(d1, d2, d3).map(domainRepo.save(_))

          domainRepo.get("google.com").get === sd1
          domainRepo.get("facebook.com").get === sd2
          domainRepo.get("yahoo.com").get === sd3

          domainRepo.save(sd1.withAutoSensitive(Some(true)))
          domainRepo.get(sd1.id.get).sensitive.get === true

          domainRepo.save(sd2.withAutoSensitive(None))
          domainRepo.get(sd2.id.get).sensitive === None
        }
      }
    }
    "respect manual sensitivity override" in {
      running(new EmptyApplication()) {
        val domainRepo = inject[DomainRepo]

        val d = Domain(hostname = "google.com", autoSensitive = Some(false))

        inject[Database].readWrite { implicit c =>
          val sd = domainRepo.save(d)

          domainRepo.get(sd.id.get).sensitive.get === false

          domainRepo.save(sd.withManualSensitive(Some(true)))
          domainRepo.get(sd.id.get).sensitive.get === true

          domainRepo.save(sd.withManualSensitive(None).withAutoSensitive(None))
          domainRepo.get(sd.id.get).sensitive === None
        }
      }
    }
  }
}
