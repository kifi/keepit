package com.keepit.classify

import org.specs2.mutable.SpecificationLike

import com.keepit.common.db.slick.Database
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.inject._
import com.keepit.test.{ShoeboxApplicationInjector, ShoeboxApplication}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.test.Helpers.running
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.akka.TestKitScope
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.heimdal.TestHeimdalServiceClientModule

class DomainClassifierTest extends TestKit(ActorSystem()) with SpecificationLike with ShoeboxApplicationInjector {

  val domainClassifierTestModules = Seq(
    FakeMailModule(),
    TestAnalyticsModule(),
    ShoeboxFakeStoreModule(),
    TestHeimdalServiceClientModule(),
    FakeDomainTagImporterModule(),
    TestActorSystemModule(Some(system)),
    FakeShoeboxServiceModule(),
    TestSearchServiceClientModule(),
    FakeAirbrakeModule()
  )

  "The domain classifier" should {
    "use imported classifications and not fetch for known domains" in {
      running(new ShoeboxApplication(domainClassifierTestModules :+ FakeHttpClientModule():_*)) {
        val classifier = inject[DomainClassifierImpl]
        val tagRepo = inject[DomainTagRepo]
        val importer = inject[DomainTagImporterImpl]

        inject[Database].readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("Search Engines"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Technology and computers"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Porn"), sensitive = Some(true)))
        }

        importer.applyTagToDomains(DomainTagName("search engines"), Seq("google.com", "yahoo.com"))
        importer.applyTagToDomains(DomainTagName("Technology and Computers"), Seq("42go.com", "google.com"))
        importer.applyTagToDomains(DomainTagName("Porn"), Seq("playboy.com"))

        classifier.isSensitive("google.com") === Right(false)
        classifier.isSensitive("yahoo.com") === Right(false)
        classifier.isSensitive("42go.com") === Right(false)
        classifier.isSensitive("playboy.com") === Right(true)
      }
    }
    "fetch if necessary" in {
      running(new ShoeboxApplication(domainClassifierTestModules :+ FakeHttpClientModule {
        case s if s.url.contains("yahoo.com") => "FR~Search engines"
        case s if s.url.contains("zdnet.com") => "FM~Technology and computers,News and magazines"
        case s if s.url.contains("schwab.com") => "FM~Business and services,Finance (Banks, Real estate, Insurance)"
        case s if s.url.contains("hover.com") => "FM~Business and services,Web hosting"
        case s if s.url.contains("42go.com") || s.url.contains("addepar.com") => "FM~Technology and computers"
        case s if s.url.contains("playboy.com") || s.url.contains("porn.com") => "FM~Porn"
      }:_*)) {
        val classifier = inject[DomainClassifierImpl]
        val domainRepo = inject[DomainRepo]
        val tagRepo = inject[DomainTagRepo]
        inject[Database].readWrite { implicit s =>
          tagRepo.save(DomainTag(name = DomainTagName("Search Engines"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Technology and computers"), sensitive = Some(false)))
          tagRepo.save(DomainTag(name = DomainTagName("Porn"), sensitive = Some(true)))
          tagRepo.save(
            DomainTag(name = DomainTagName("Finance (Banks, Real estate, Insurance)"), sensitive = Some(true)))

          domainRepo.save(Domain(hostname = "google.com", manualSensitive = Some(false)))
        }

        classifier.isSensitive("google.com") === Right(false)

        classifier.isSensitive("yahoo.com").left.get
        classifier.isSensitive("zdnet.com").left.get
        classifier.isSensitive("schwab.com").left.get
        classifier.isSensitive("hover.com").left.get
        classifier.isSensitive("42go.com").left.get
        classifier.isSensitive("addepar.com").left.get
        classifier.isSensitive("playboy.com").left.get
        classifier.isSensitive("porn.com").left.get

        classifier.isSensitive("www.yahoo.com") === Right(false)
        classifier.isSensitive("yahoo.com") === Right(false)
        classifier.isSensitive("zdnet.com") === Right(false)
        classifier.isSensitive("schwab.com") === Right(true)
        classifier.isSensitive("hover.com") === Right(false)
        classifier.isSensitive("42go.com") === Right(false)
        classifier.isSensitive("addepar.com") === Right(false)
        classifier.isSensitive("playboy.com") === Right(true)
        classifier.isSensitive("www.porn.com") === Right(true)
        classifier.isSensitive("porn.com") === Right(true)

        classifier.isSensitive("local-domain") === Right(true)
      }
    }
  }
}
