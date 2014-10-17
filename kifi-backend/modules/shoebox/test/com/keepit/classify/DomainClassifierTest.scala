package com.keepit.classify

import com.keepit.common.actor.{ TestKitSupport, FakeActorSystemModule }
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.{ TestDbInfo, FakeSlickModule }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ CommonTestInjector, DbInjectionHelper }
import org.specs2.mutable.SpecificationLike

class DomainClassifierTest extends TestKitSupport with SpecificationLike with CommonTestInjector with DbInjectionHelper {

  val domainClassifierTestModules = Seq(
    FakeExecutionContextModule(),
    FakeMailModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeHeimdalServiceClientModule(),
    FakeDomainTagImporterModule(),
    FakeActorSystemModule(),
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    ShoeboxCacheModule(HashMapMemoryCacheModule()),
    FakeAirbrakeModule()
  )

  "The domain classifier" should {
    "use imported classifications and not fetch for known domains" in {
      withDb(domainClassifierTestModules :+ FakeHttpClientModule(): _*) { implicit injector =>
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
      withDb(domainClassifierTestModules :+ FakeHttpClientModule {
        case s if s.url.contains("yahoo.com") => "FR~Search engines"
        case s if s.url.contains("zdnet.com") => "FM~Technology and computers,News and magazines"
        case s if s.url.contains("schwab.com") => "FM~Business and services,Finance (Banks, Real estate, Insurance)"
        case s if s.url.contains("hover.com") => "FM~Business and services,Web hosting"
        case s if s.url.contains("42go.com") || s.url.contains("addepar.com") => "FM~Technology and computers"
        case s if s.url.contains("playboy.com") || s.url.contains("porn.com") => "FM~Porn"
      }: _*) { implicit injector =>
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
