package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.common.db.Id
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike

class NewKeepsInLibraryCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeScrapeSchedulerModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule()
  )

  def setupUsers()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val emailRepo = inject[UserEmailAddressRepo]
    val emailIron = EmailAddress("tony@stark.com")
    val emailCaptain = EmailAddress("steve.rogers@hotmail.com")
    val emailAgent = EmailAddress("samuelljackson@shield.com")
    val emailHulk = EmailAddress("incrediblehulk@gmail.com")

    val (userIron, userCaptain, userAgent, userHulk) = db.readWrite { implicit s =>
      val userIron = userRepo.save(User(firstName = "Tony", lastName = "Stark", createdAt = t1, primaryEmail = Some(emailIron), username = Some(Username("ironman"))))
      val userCaptain = userRepo.save(User(firstName = "Steve", lastName = "Rogers", createdAt = t1, primaryEmail = Some(emailCaptain), username = Some(Username("captainamerica"))))
      val userAgent = userRepo.save(User(firstName = "Nick", lastName = "Fury", createdAt = t1, primaryEmail = Some(emailAgent), username = Some(Username("agentfury"))))
      val userHulk = userRepo.save(User(firstName = "Bruce", lastName = "Banner", createdAt = t1, primaryEmail = Some(emailHulk), username = Some(Username("incrediblehulk"))))

      emailRepo.save(UserEmailAddress(userId = userIron.id.get, address = emailIron))
      emailRepo.save(UserEmailAddress(userId = userCaptain.id.get, address = emailCaptain))
      emailRepo.save(UserEmailAddress(userId = userAgent.id.get, address = emailAgent))
      emailRepo.save(UserEmailAddress(userId = userHulk.id.get, address = emailHulk))

      (userIron, userCaptain, userAgent, userHulk)
    }
    db.readOnlyMaster { implicit s =>
      userRepo.count === 4
    }
    (userIron, userCaptain, userAgent, userHulk)
  }

  def setupLibraries()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk) = setupUsers
    val t1 = new DateTime(2014, 8, 1, 1, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val t2 = new DateTime(2014, 8, 1, 1, 0, 0, 1, DEFAULT_DATE_TIME_ZONE)
    val (libShield, libMurica, libScience) = db.readWrite { implicit s =>
      val libShield = libraryRepo.save(Library(name = "Avengers Missions", slug = LibrarySlug("avengers"),
        visibility = LibraryVisibility.SECRET, ownerId = userAgent.id.get, createdAt = t1, memberCount = 1))
      val libMurica = libraryRepo.save(Library(name = "MURICA", slug = LibrarySlug("murica"),
        visibility = LibraryVisibility.PUBLISHED, ownerId = userCaptain.id.get, createdAt = t1, memberCount = 1))
      val libScience = libraryRepo.save(Library(name = "Science & Stuff", slug = LibrarySlug("science"),
        visibility = LibraryVisibility.DISCOVERABLE, ownerId = userIron.id.get, createdAt = t1, memberCount = 1))

      libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = userAgent.id.get, access = LibraryAccess.READ_ONLY, createdAt = t2, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, createdAt = t2, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libMurica.id.get, userId = userCaptain.id.get, access = LibraryAccess.READ_ONLY, createdAt = t2, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libScience.id.get, userId = userIron.id.get, access = LibraryAccess.READ_ONLY, createdAt = t2, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libMurica.id.get, userId = userAgent.id.get, access = LibraryAccess.READ_ONLY, createdAt = t2, showInSearch = true))
      (libShield, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      val allLibs = libraryRepo.all
      allLibs.length === 3
      allLibs.map(_.name) === Seq("Avengers Missions", "MURICA", "Science & Stuff")
      allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
      allLibs.map(_.description) === Seq(None, None, None)
      allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.PUBLISHED, LibraryVisibility.DISCOVERABLE)
      libraryMembershipRepo.count === 5
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupKeeps()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries()
    val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val site1 = "http://www.reddit.com/r/murica"
    val site2 = "http://www.freedom.org/"
    val site3 = "http://www.mcdonalds.com/"

    val keeps: Seq[Keep] = db.readWrite { implicit s =>
      val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
      val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
      val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))
      val uri4 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds1")))
      val uri5 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds2")))
      val uri6 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds3")).copy(restriction = Some(Restriction.ADULT)))
      val uri7 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds4")))
      val uri8 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds5")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
      val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))
      val url4 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri4.id.get))
      val url5 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri5.id.get))
      val url6 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri6.id.get))
      val url7 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri7.id.get))
      val url8 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri8.id.get))

      Seq(
        keepRepo.save(Keep(title = Some("Reddit"), userId = userCaptain.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(20),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libShield.id.get), inDisjointLib = libShield.isDisjoint)),
        keepRepo.save(Keep(title = Some("Freedom"), userId = userCaptain.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(100),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libShield.id.get), inDisjointLib = libShield.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds"), userId = userIron.id.get, url = url3.url, urlId = url3.id.get,
          uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(15),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds1"), userId = userIron.id.get, url = url4.url, urlId = url4.id.get,
          uriId = uri4.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(25),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds2"), userId = userIron.id.get, url = url5.url, urlId = url5.id.get,
          uriId = uri5.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(35),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds3"), userId = userIron.id.get, url = url6.url, urlId = url6.id.get,
          uriId = uri6.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(45),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds4"), userId = userIron.id.get, url = url7.url, urlId = url7.id.get,
          uriId = uri7.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(55),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds5"), userId = userIron.id.get, url = url8.url, urlId = url8.id.get,
          uriId = uri8.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(65),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)))
    }
    db.readOnlyMaster { implicit s =>
      keepRepo.count === 8
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience, keeps.map(_.id.get))
  }

  "LibraryCommander" should {
    "create libraries, memberships & invites" in {
      withDb(modules: _*) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience, keeps: Seq[Id[Keep]]) = setupKeeps()
        val commander = inject[NewKeepsInLibraryCommander]
        commander.getLastViewdKeeps(userIron.id.get, 100).size === 0
        commander.getLastViewdKeeps(userCaptain.id.get, 100).map(_.id.get) === Seq(keeps(2), keeps(3), keeps(4), keeps(6), keeps(7))
        commander.getLastViewdKeeps(userAgent.id.get, 100).map(_.id.get) === Seq(keeps(2), keeps(0), keeps(3), keeps(4), keeps(6), keeps(7), keeps(1))
        commander.getLastViewdKeeps(userAgent.id.get, 2).map(_.id.get) === Seq(keeps(2), keeps(0))
        commander.getLastViewdKeeps(userCaptain.id.get, 2).map(_.id.get) === Seq(keeps(2), keeps(3))
        commander.getLastViewdKeeps(userAgent.id.get, 1).map(_.id.get) === Seq(keeps(2))
        commander.getLastViewdKeeps(userCaptain.id.get, 1).map(_.id.get) === Seq(keeps(2))
      }
    }
  }
}
