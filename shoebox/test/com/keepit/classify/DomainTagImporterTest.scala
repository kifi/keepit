package com.keepit.classify

import scala.io.Source

import org.specs2.mutable._

import com.keepit.common.db.slick.DBConnection
import com.keepit.inject._
import com.keepit.test._

import play.api.Play.current
import play.api.test.Helpers._


class DomainTagImporterTest extends SpecificationWithJUnit with DbRepos {
  "The domain tag importer" should {
    "load domain sensitivity from a map of tags to domains" in {
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainTagImporter = inject[DomainTagImporter]

        val db = inject[DBConnection]
        db.readWrite { implicit s =>
          // add some existing tags
          tagRepo.save(DomainTag(name = DomainTagName("t1"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t2"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t3"), sensitive = Option(true)))

          domainTagImporter.applyTagToDomains(
            DomainTagName("t1"), Set("cnn.com", "yahoo.com", "google.com"))
          domainTagImporter.applyTagToDomains(
            DomainTagName("t2"), Set("cnn.com", "amazon.com", "apple.com"))
          domainTagImporter.applyTagToDomains(
            DomainTagName("t3"), Set("apple.com", "42go.com", "methvin.net"))
          // add a new tag (unknown sensitivity)
          domainTagImporter.applyTagToDomains(
            DomainTagName("t4"), Set("42go.com", "amazon.com", "wikipedia.org"))
        }

        db.readOnly { implicit s =>
          domainRepo.get("apple.com").get.sensitive === Some(true)
          domainRepo.get("amazon.com").get.sensitive === None
          domainRepo.get("google.com").get.sensitive === Some(false)
          domainRepo.get("methvin.net").get.sensitive === Some(true)
          domainRepo.get("42go.com").get.sensitive === Some(true)
          domainRepo.get("wikipedia.org").get.sensitive === None
        }
      }
    }
    "properly remove domain tags" in {
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainTagImporter = inject[DomainTagImporter]

        val db = inject[DBConnection]
        db.readWrite { implicit s =>
        // add some existing tags
          tagRepo.save(DomainTag(name = DomainTagName("t1"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t2"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("t3"), sensitive = Some(true)))

          domainTagImporter.applyTagToDomains(
            DomainTagName("t1"), Set("cnn.com", "yahoo.com", "google.com"))
          domainTagImporter.applyTagToDomains(
            DomainTagName("t2"), Set("cnn.com", "amazon.com", "apple.com"))
          domainTagImporter.applyTagToDomains(
            DomainTagName("t2"), Set("cnn.com", "amazon.com", "apple.com"))
          domainTagImporter.applyTagToDomains(
            DomainTagName("t3"), Set("apple.com", "42go.com", "methvin.net"))
          // remove a tag
          domainTagImporter.removeTag(DomainTagName("t3"))
        }

        db.readOnly { implicit s =>
          domainRepo.get("apple.com").get.sensitive === Some(false)
          domainRepo.get("amazon.com").get.sensitive === Some(false)
          domainRepo.get("google.com").get.sensitive === Some(false)
          domainRepo.get("methvin.net").get.sensitive === Some(false)
          domainRepo.get("42go.com").get.sensitive === Some(false)
        }
      }
    }
    "respect manual overrides" in {
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainTagImporter = inject[DomainTagImporter]

        val db = inject[DBConnection]
        db.readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("things"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("stuff"), sensitive = None))

          domainTagImporter.applyTagToDomains(DomainTagName("things"), Set("cnn.com", "yahoo.com", "google.com"))
          domainRepo.get("cnn.com").get.sensitive === Some(false)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(Some(true)))
          domainRepo.get("cnn.com").get.sensitive === Some(true)
          domainTagImporter.applyTagToDomains(DomainTagName("stuff"), Set("apple.com", "microsoft.com", "cnn.com"))
          domainRepo.get("cnn.com").get.sensitive === Some(true)
          domainRepo.save(domainRepo.get("cnn.com").get.withManualSensitive(None))
          domainRepo.get("cnn.com").get.sensitive === None
        }
      }
    }

    "work with domain tags loaded from a file" in {
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]
        val domainRepo = inject[DomainRepo]
        val domainTagImporter = inject[DomainTagImporter]

        val db = inject[DBConnection]
        db.readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("Humor"), sensitive = Option(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Drugs"), sensitive = Option(true)))
          tagRepo.save(DomainTag(name = DomainTagName("Jobs"), sensitive = None))
          val path = "test/com/keepit/classify/"
          val drugsDomains = Source.fromFile(path + "drugs_domains.txt")
            .getLines().map(_.trim).filter(!_.isEmpty).toSet
          val humorDomains = Source.fromFile(path + "humor_domains.txt")
            .getLines().map(_.trim).filter(!_.isEmpty).toSet
          val jobsDomains = Source.fromFile(path + "jobs_domains.txt")
            .getLines().map(_.trim).filter(!_.isEmpty).toSet
          domainTagImporter.applyTagToDomains(DomainTagName("Drugs"), drugsDomains)
          domainTagImporter.applyTagToDomains(DomainTagName("Humor"), humorDomains)
          domainTagImporter.applyTagToDomains(DomainTagName("Jobs"), jobsDomains)
          domainRepo.all().map { domain =>
            // assume these are lists of disjoint domains
            if (drugsDomains contains domain.hostname) domain.sensitive === Some(true)
            if (humorDomains contains domain.hostname) domain.sensitive === Some(false)
            if (jobsDomains contains domain.hostname) domain.sensitive === None
          }
          true
        }
      }
    }
  }
}
