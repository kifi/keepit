package com.keepit.classify

import org.specs2.mutable._
import com.keepit.common.db.slick.Database
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.Normalization

class DomainTest extends Specification with ShoeboxTestInjector {
  "The domain repo" should {
    "save and retrieve domains by name and id" in {
      withDb() { implicit injector =>
        val domainRepo = inject[DomainRepo]

        val d1 = Domain(hostname = NormalizedHostname.fromHostname("google.com"), autoSensitive = Some(false))
        val d2 = Domain(hostname = NormalizedHostname.fromHostname("facebook.com"), autoSensitive = Some(false))
        val d3 = Domain(hostname = NormalizedHostname.fromHostname("yahoo.com"), autoSensitive = Some(false))

        inject[Database].readOnlyMaster { implicit c =>
          domainRepo.get("google.com") === None
          domainRepo.get("facebook.com") === None
          domainRepo.get("yahoo.com") === None
        }
        val Seq(sd1, sd2, sd3) = inject[Database].readWrite { implicit c =>
          Seq(d1, d2, d3).map(domainRepo.save(_))
        }
        inject[Database].readOnlyMaster { implicit c =>
          domainRepo.get("google.com").get === sd1
          domainRepo.get("facebook.com").get === sd2
          domainRepo.get("yahoo.com").get === sd3
        }
        inject[Database].readWrite { implicit c =>
          domainRepo.save(sd1.withAutoSensitive(Some(true)))
          domainRepo.get(sd1.id.get).sensitive.get === true

          domainRepo.save(sd2.withAutoSensitive(None))
          domainRepo.get(sd2.id.get).sensitive === None
        }
      }
    }
    "respect manual sensitivity override" in {
      withDb() { implicit injector =>
        val domainRepo = inject[DomainRepo]

        val d = Domain(hostname = NormalizedHostname.fromHostname("google.com"), autoSensitive = Some(false))

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
    "intern domains by hostname" in {
      withDb() { implicit injector =>
        val domainRepo = inject[DomainRepo]
        val domain1 = Domain(hostname = NormalizedHostname.fromHostname("google.com"))
        val domain2 = Domain(hostname = NormalizedHostname.fromHostname("apple.com"))

        inject[Database].readWrite { implicit c =>
          val saved = domainRepo.save(domain1)
          val inactive = domainRepo.save(domain2.copy(state = DomainStates.INACTIVE))

          val internedDomains = domainRepo.internAllByNames(Set("google.com", "apple.com"))
          internedDomains.keys.toSet === Set("google.com", "apple.com")
        }
      }
    }

    "get domains by hash" in {
      withDb() { implicit injector =>
        val domainRepo = inject[DomainRepo]
        val domain1 = Domain.fromHostname(hostname = "google.com")
        val domain2 = Domain.fromHostname(hostname = "Ğooğle.com")

        inject[Database].readWrite { implicit session =>
          val saved = domainRepo.save(domain1)
          val saved1 = domainRepo.save(domain2)

          val actual = domainRepo.get("google.com").headOption
          actual.isDefined === true
          actual.get.hostname.value === "google.com"

          val actual2 = domainRepo.get("Google.com").headOption
          actual2.isDefined === true
          actual2.get.hostname.value === "google.com"

          val actual3 = domainRepo.get("Ğooğle.com").headOption
          actual3.isDefined === true
          actual3.get.hostname === NormalizedHostname.fromHostname("Ğooğle.com")
          actual3.get.hostname.value === "xn--oole-zwac.com"
        }
      }
    }
  }
}
