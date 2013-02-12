package com.keepit.classify

import org.specs2.mutable.SpecificationWithJUnit

import com.keepit.common.db.slick.DBConnection
import com.keepit.common.net.FakeHttpClient
import com.keepit.inject.inject
import com.keepit.test.{DbRepos, EmptyApplication}

import akka.actor.ActorSystem
import akka.dispatch.Await
import akka.util.duration._
import play.api.Play.current
import play.api.test.Helpers.running


class DomainClassifierTest extends SpecificationWithJUnit with DbRepos {
  val system = ActorSystem("system")
  "The domain classifier" should {
    "use imported classifications and not fetch for known domains" in {
      running(new EmptyApplication()) {
        val client = new FakeHttpClient(Some(Map.empty))

        val classifier = new DomainClassifierImpl(system, inject[DBConnection], client,
          inject[SensitivityUpdater], inject[DomainRepo], inject[DomainTagRepo], inject[DomainToTagRepo])
        val tagRepo = inject[DomainTagRepo]
        val importer = inject[DomainTagImporter]
        inject[DBConnection].readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("Search Engines"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Technology and computers"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Porn"), sensitive = Some(true)))

          importer.applyTagToDomains(DomainTagName("search engines"), Seq("google.com", "yahoo.com"))
          importer.applyTagToDomains(DomainTagName("Technology and Computers"), Seq("42go.com", "google.com"))
          importer.applyTagToDomains(DomainTagName("Porn"), Seq("playboy.com"))
        }

        classifier.isSensitive("google.com") === Right(Some(false))
        classifier.isSensitive("yahoo.com") === Right(Some(false))
        classifier.isSensitive("42go.com") === Right(Some(false))
        classifier.isSensitive("playboy.com") === Right(Some(true))
      }
    }
    "fetch if necessary" in {
      running(new EmptyApplication()) {
        val client = new FakeHttpClient(Some({
          case s if s.contains("yahoo.com") => "FR~Search engines"
          case s if s.contains("zdnet.com") => "FM~Technology and computers,News and magazines"
          case s if s.contains("schwab.com") => "FM~Business and services,Finance (Banks, Real estate, Insurance)"
          case s if s.contains("hover.com") => "FM~Business and services,Web hosting"
          case s if s.contains("42go.com") || s.contains("addepar.com") => "FM~Technology and computers"
          case s if s.contains("playboy.com") || s.contains("porn.com") => "FM~Porn"
        }))
        val classifier = new DomainClassifierImpl(system, inject[DBConnection], client,
          inject[SensitivityUpdater], inject[DomainRepo], inject[DomainTagRepo], inject[DomainToTagRepo])
        val domainRepo = inject[DomainRepo]
        val tagRepo = inject[DomainTagRepo]
        inject[DBConnection].readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("Search Engines"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Technology and computers"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Porn"), sensitive = Some(true)))
          tagRepo.save(
            DomainTag(name = DomainTagName("Finance (Banks, Real estate, Insurance)"), sensitive = Some(true)))

          domainRepo.save(Domain(hostname = "google.com", manualSensitive = Some(false)))
        }

        classifier.isSensitive("google.com") === Right(Some(false))

        Seq(
          classifier.isSensitive("yahoo.com").left.get,
          classifier.isSensitive("zdnet.com").left.get,
          classifier.isSensitive("schwab.com").left.get,
          classifier.isSensitive("hover.com").left.get,
          classifier.isSensitive("42go.com").left.get,
          classifier.isSensitive("addepar.com").left.get,
          classifier.isSensitive("playboy.com").left.get,
          classifier.isSensitive("porn.com").left.get
        ).foreach { future =>
          Await.result(future, intToDurationInt(10).millis)
        }

        classifier.isSensitive("yahoo.com") === Right(Some(false))
        classifier.isSensitive("zdnet.com") === Right(None)
        classifier.isSensitive("schwab.com") === Right(Some(true))
        classifier.isSensitive("hover.com") === Right(None)
        classifier.isSensitive("42go.com") === Right(Some(false))
        classifier.isSensitive("addepar.com") === Right(Some(false))
        classifier.isSensitive("playboy.com") === Right(Some(true))
        classifier.isSensitive("porn.com") === Right(Some(true))
      }
    }
  }
}
