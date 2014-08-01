package com.keepit.classify

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.test._

import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.actor.{ TestKitSupport, TestActorSystemModule }
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.TestHeimdalServiceClientModule

class DomainTagImporterTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val domainTagImporterTestModules = Seq(
    FakeMailModule(),
    FakeAnalyticsModule(),
    TestHeimdalServiceClientModule(),
    ShoeboxFakeStoreModule(),
    FakeDomainTagImporterModule(),
    TestActorSystemModule(Some(system)),
    TestSearchServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeAirbrakeModule(),
    FakeElizaServiceClientModule()
  )

  "The domain tag importer" should {
    "load domain sensitivity from a map of tags to domains" in {
      withDb(domainTagImporterTestModules: _*) { implicit injector =>
        val db = inject[Database]
        val tagRepo = inject[DomainTagRepo]
        val domainTagImporter = inject[DomainTagImporterImpl]
        val domainRepo = inject[DomainRepo]
        db.readWrite { implicit s =>
          // add some existing tags
          tagRepo.save(DomainTag(name = DomainTagName("t1"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t2"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t3"), sensitive = Option(true)))
        }

        domainTagImporter.applyTagToDomains(DomainTagName("t1"), Seq("cnn.com", "yahoo.com", "google.com"))
        domainTagImporter.applyTagToDomains(DomainTagName("t2"), Seq("cnn.com", "amazon.com", "apple.com"))
        domainTagImporter.applyTagToDomains(DomainTagName("t3"), Seq("apple.com", "42go.com", "methvin.net"))
        // add a new tag (unknown sensitivity)
        domainTagImporter.applyTagToDomains(DomainTagName("t4"), Seq("42go.com", "amazon.com", "wikipedia.org"))

        db.readWrite { implicit s =>
          Seq("apple.com", "amazon.com", "google.com", "methvin.net", "42go.com", "wikipedia.org")
            .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)
        }

        db.readOnlyMaster { implicit s =>
          domainRepo.get("apple.com").get.sensitive === Some(true)
          domainRepo.get("amazon.com").get.sensitive === Some(false)
          domainRepo.get("google.com").get.sensitive === Some(false)
          domainRepo.get("methvin.net").get.sensitive === Some(true)
          domainRepo.get("42go.com").get.sensitive === Some(true)
          domainRepo.get("wikipedia.org").get.sensitive === Some(false)
        }
      }
    }
    "properly remove domain tags" in {
      withDb(domainTagImporterTestModules: _*) { implicit injector =>
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val db = inject[Database]
        val domainTagImporter = inject[DomainTagImporterImpl]
        db.readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("t1"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t2"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t3"), sensitive = Some(true)))
        }

        domainTagImporter.applyTagToDomains(DomainTagName("t1"), Seq("cnn.com", "yahoo.com", "google.com"))
        domainTagImporter.applyTagToDomains(DomainTagName("t2"), Seq("cnn.com", "amazon.com", "apple.com"))
        domainTagImporter.applyTagToDomains(DomainTagName("t2"), Seq("cnn.com", "amazon.com", "apple.com"))
        domainTagImporter.applyTagToDomains(DomainTagName("t3"), Seq("apple.com", "42go.com", "methvin.net"))
        // remove a tag
        domainTagImporter.removeTag(DomainTagName("t3"))

        db.readWrite { implicit s =>
          Seq("apple.com", "amazon.com", "google.com", "methvin.net", "42go.com")
            .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)
        }

        db.readOnlyMaster { implicit s =>
          domainRepo.get("apple.com").get.sensitive === Some(false)
          domainRepo.get("amazon.com").get.sensitive === Some(false)
          domainRepo.get("google.com").get.sensitive === Some(false)
          domainRepo.get("methvin.net").get.sensitive === None
          domainRepo.get("42go.com").get.sensitive === None
        }
      }
    }
    "respect manual overrides" in {
      withDb(domainTagImporterTestModules: _*) { implicit injector =>
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val db = inject[Database]
        val domainTagImporter = inject[DomainTagImporterImpl]

        db.readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("things"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("stuff"), sensitive = None))
        }

        domainTagImporter.applyTagToDomains(DomainTagName("things"), Set("cnn.com", "yahoo.com", "google.com").toSeq)
        db.readWrite { implicit s =>
          Seq("cnn.com", "yahoo.com", "google.com")
            .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)

          domainRepo.get("cnn.com").get.sensitive === Some(false)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(Some(true)))
        }
        db.readOnlyMaster { implicit s =>
          domainRepo.get("cnn.com").get.sensitive === Some(true)
        }

        domainTagImporter.applyTagToDomains(DomainTagName("stuff"), Set("apple.com", "microsoft.com", "cnn.com").toSeq)
        db.readWrite { implicit s =>
          Seq("apple.com", "microsoft.com", "cnn.com")
            .map(domainRepo.get(_).get).map(inject[SensitivityUpdater].calculateSensitivity)

          domainRepo.get("cnn.com").get.sensitive === Some(true)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(None))
        }
        db.readOnlyMaster { implicit s =>
          domainRepo.get("cnn.com").get.sensitive === Some(false)
        }
      }
    }
  }
}
