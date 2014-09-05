package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.{ Library, LibraryAccess, LibraryKind, LibraryMembership, LibraryMembershipRepo, LibraryRepo, LibrarySlug, LibraryVisibility, UrlPatternRuleModule, User }
import com.keepit.scraper.{ FakeScrapeSchedulerConfigModule, FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class ShoeboxDataPipeCommanderTest extends Specification with ShoeboxTestInjector {
  val shoeboxControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeActionAuthenticatorModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeScrapeSchedulerConfigModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule(),
    UrlPatternRuleModule(),
    FakeCuratorServiceClientModule()
  )

  def setupLibAndMembership() = {

    val membership1 = LibraryMembership(
      libraryId = Id[Library](1),
      userId = Id[User](42),
      access = LibraryAccess.OWNER,
      showInSearch = true,
      seq = SequenceNumber[LibraryMembership](1)
    )
    val membership2 = LibraryMembership(
      libraryId = Id[Library](2),
      userId = Id[User](42),
      access = LibraryAccess.READ_WRITE,
      showInSearch = true,
      seq = SequenceNumber[LibraryMembership](2)
    )
    val membership3 = LibraryMembership(
      libraryId = Id[Library](2),
      userId = Id[User](43),
      access = LibraryAccess.OWNER,
      showInSearch = true,
      seq = SequenceNumber[LibraryMembership](3)
    )

    val lib1 = Library(
      name = "lib1",
      ownerId = Id[User](42),
      kind = LibraryKind.SYSTEM_MAIN,
      visibility = LibraryVisibility.DISCOVERABLE,
      slug = LibrarySlug("good"),
      memberCount = 10
    )
    val lib2 = Library(
      name = "lib2",
      ownerId = Id[User](42),
      kind = LibraryKind.USER_CREATED,
      visibility = LibraryVisibility.DISCOVERABLE,
      slug = LibrarySlug("good"),
      memberCount = 10
    )

    (membership1, membership2, membership3, lib1, lib2)
  }

  "ShoeboxDataPipeControllerTest" should {
    "get libraryMembership" in {

      withDb(shoeboxControllerTestModules: _*) { implicit injector =>
        val setups = setupLibAndMembership()
        val commander = inject[ShoeboxDataPipeCommander]
        val libRepo = inject[LibraryRepo]
        val libMembershipRepo = inject[LibraryMembershipRepo]

        db.readWrite { implicit s =>
          libRepo.save(setups._4)
          libRepo.save(setups._5)
          libMembershipRepo.save(setups._1)
          libMembershipRepo.save(setups._2)
          libMembershipRepo.save(setups._3)
          libMembershipRepo.assignSequenceNumbers(100)
        }

        db.readOnlyMaster { implicit s => libMembershipRepo.all() }.size === 3
        val result = commander.getLibraryMembershipChanged(SequenceNumber.ZERO[LibraryMembership], 10)
        result.size === 2
        result(0).libraryId === Id[Library](2)

      }
    }
  }

}
