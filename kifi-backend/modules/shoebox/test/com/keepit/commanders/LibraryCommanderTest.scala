package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.{ PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.db.{ Id }
import com.keepit.common.mail.{ ElectronicMailRepo, FakeMailModule, EmailAddress }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, FakeElizaServiceClientImpl, FakeElizaServiceClientModule }
import com.keepit.heimdal.{ HeimdalContext, FakeHeimdalServiceClientModule }
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.{ SpecificationLike }

import scala.concurrent._
import scala.concurrent.duration.Duration

class LibraryCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
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
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  def setupUsers()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val emailRepo = inject[UserEmailAddressRepo]
    val emailIron = EmailAddress("tony@stark.com")
    val emailCaptain = EmailAddress("steve.rogers@hotmail.com")
    val emailAgent = EmailAddress("samuelljackson@shield.com")
    val emailHulk = EmailAddress("incrediblehulk@gmail.com")

    val (userIron, userCaptain, userAgent, userHulk) = db.readWrite { implicit s =>
      val userIron = userRepo.save(User(firstName = "Tony", lastName = "Stark", createdAt = t1, primaryEmail = Some(emailIron), username = Username("ironman"), normalizedUsername = "foo1"))
      val userCaptain = userRepo.save(User(firstName = "Steve", lastName = "Rogers", createdAt = t1, primaryEmail = Some(emailCaptain), username = Username("captainamerica"), normalizedUsername = "foo2"))
      val userAgent = userRepo.save(User(firstName = "Nick", lastName = "Fury", createdAt = t1, primaryEmail = Some(emailAgent), username = Username("agentfury"), normalizedUsername = "foo3"))
      val userHulk = userRepo.save(User(firstName = "Bruce", lastName = "Banner", createdAt = t1, primaryEmail = Some(emailHulk), username = Username("incrediblehulk"), normalizedUsername = "foo4"))

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

      libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = userAgent.id.get, access = LibraryAccess.OWNER, createdAt = t2, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libMurica.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER, createdAt = t2, showInSearch = true))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libScience.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, createdAt = t2, showInSearch = true))
      (libShield, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      val allLibs = libraryRepo.all
      allLibs.length === 3
      allLibs.map(_.name) === Seq("Avengers Missions", "MURICA", "Science & Stuff")
      allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
      allLibs.map(_.description) === Seq(None, None, None)
      allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.PUBLISHED, LibraryVisibility.DISCOVERABLE)
      libraryMembershipRepo.count === 3
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

    val t1 = new DateTime(2014, 8, 1, 2, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      // Everybody loves Murica! Follow Captain America's library
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, ownerId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, ownerId = userCaptain.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, ownerId = userCaptain.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))

      // Ironman invites the Hulk to contribute to 'Science & Stuff'
      libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, ownerId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_INSERT, createdAt = t1))

      (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      libraryInviteRepo.count === 4
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupAcceptedInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
    val t1 = new DateTime(2014, 8, 1, 3, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      // Hulk accepts Ironman's invite to see 'Science & Stuff'
      val inv1 = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libScience.id.get, userId = userHulk.id.get).head
      libraryInviteRepo.save(inv1.withState(LibraryInviteStates.ACCEPTED))

      // Ironman & NickFury accept Captain's invite to see 'MURICA'
      val inv2 = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libMurica.id.get, userId = userIron.id.get).head
      libraryInviteRepo.save(inv2.withState(LibraryInviteStates.ACCEPTED))
      val inv3 = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libMurica.id.get, userId = userAgent.id.get).head
      libraryInviteRepo.save(inv3.withState(LibraryInviteStates.ACCEPTED))

      libraryMembershipRepo.save(LibraryMembership(libraryId = inv1.libraryId, userId = inv1.userId.get, access = inv1.access, showInSearch = true, createdAt = t1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = inv2.libraryId, userId = inv2.userId.get, access = inv2.access, showInSearch = true, createdAt = t1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = inv3.libraryId, userId = inv3.userId.get, access = inv3.access, showInSearch = true, createdAt = t1))
      libraryRepo.save(libMurica.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libMurica.id.get)))
      libraryRepo.save(libScience.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libScience.id.get)))
    }
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.count === 6
      libraryRepo.get(libMurica.id.get).memberCount === 3
      libraryRepo.get(libScience.id.get).memberCount === 2
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupKeeps()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
    val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val site1 = "http://www.reddit.com/r/murica"
    val site2 = "http://www.freedom.org/"
    val site3 = "http://www.mcdonalds.com/"

    db.readWrite { implicit s =>
      val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
      val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
      val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
      val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

      val keep1 = keepRepo.save(Keep(title = Some("Reddit"), userId = userCaptain.id.get, url = url1.url, urlId = url1.id.get,
        uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
      val keep2 = keepRepo.save(Keep(title = Some("Freedom"), userId = userCaptain.id.get, url = url2.url, urlId = url2.id.get,
        uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(15),
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
      val keep3 = keepRepo.save(Keep(title = Some("McDonalds"), userId = userCaptain.id.get, url = url3.url, urlId = url3.id.get,
        uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(30),
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))

      val tag1 = collectionRepo.save(Collection(userId = userCaptain.id.get, name = Hashtag("USA")))
      val tag2 = collectionRepo.save(Collection(userId = userCaptain.id.get, name = Hashtag("food")))

      keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tag1.id.get))
      keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = tag1.id.get))
      keepToCollectionRepo.save(KeepToCollection(keepId = keep3.id.get, collectionId = tag1.id.get))
      keepToCollectionRepo.save(KeepToCollection(keepId = keep3.id.get, collectionId = tag2.id.get))
    }
    db.readOnlyMaster { implicit s =>
      keepRepo.count === 3
      collectionRepo.count(userCaptain.id.get) === 2
      keepToCollectionRepo.count === 4
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  "LibraryCommander" should {
    "create libraries, memberships & invites" in {
      withDb(modules: _*) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk) = setupUsers()

        db.readOnlyMaster { implicit s =>
          libraryRepo.count === 0
        }

        val noInvites = None
        val inv2 = Some(userIron.externalId :: userAgent.externalId :: userHulk.externalId :: Nil)
        val inv3 = Some(userHulk.externalId :: Nil)

        val lib1Request = LibraryAddRequest(name = "Avengers Missions", slug = "avengers",
          visibility = LibraryVisibility.SECRET, collaborators = noInvites, followers = noInvites)

        val lib2Request = LibraryAddRequest(name = "MURICA", slug = "murica",
          visibility = LibraryVisibility.PUBLISHED, collaborators = noInvites, followers = inv2)

        val lib3Request = LibraryAddRequest(name = "Science and Stuff", slug = "science",
          visibility = LibraryVisibility.DISCOVERABLE, collaborators = inv3, followers = noInvites)

        val lib5Request = LibraryAddRequest(name = "Invalid Param", slug = "",
          visibility = LibraryVisibility.SECRET, collaborators = noInvites, followers = noInvites)

        val libraryCommander = inject[LibraryCommander]
        val add1 = libraryCommander.addLibrary(lib1Request, userAgent.id.get)
        add1.isRight === true
        add1.right.get.name === "Avengers Missions"
        val add2 = libraryCommander.addLibrary(lib2Request, userCaptain.id.get)
        add2.isRight === true
        add2.right.get.name === "MURICA"
        val add3 = libraryCommander.addLibrary(lib3Request, userIron.id.get)
        add3.isRight === true
        add3.right.get.name === "Science and Stuff"
        libraryCommander.addLibrary(lib5Request, userIron.id.get).isRight === false

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")

          val allMemberships = libraryMembershipRepo.all
          allMemberships.length === 3
          allMemberships.map(_.userId) === Seq(userAgent.id.get, userCaptain.id.get, userIron.id.get)
          allMemberships.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.OWNER, LibraryAccess.OWNER)

          val allInvites = libraryInviteRepo.all
          allInvites.length === 4
          val invitePairs = for (i <- allInvites) yield (i.ownerId, i.userId.get)

          invitePairs === (userCaptain.id.get, userIron.id.get) ::
            (userCaptain.id.get, userAgent.id.get) ::
            (userCaptain.id.get, userHulk.id.get) ::
            (userIron.id.get, userHulk.id.get) ::
            Nil
        }

        // test re-activating inactive library
        val libScience = add3.right.get
        db.readWrite { implicit s =>
          libraryRepo.save(libScience.copy(state = LibraryStates.INACTIVE))
        }
        libraryCommander.addLibrary(lib3Request, userIron.id.get).isRight === true
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
        }
      }

    }

    "modify library" in {
      withDb(modules: _*) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        val libraryCommander = inject[LibraryCommander]
        val mod1 = libraryCommander.modifyLibrary(libraryId = libShield.id.get, userId = userAgent.id.get,
          description = Some("Samuel L. Jackson was here"))
        mod1.isRight === true
        mod1.right.get.description === Some("Samuel L. Jackson was here")

        val mod2 = libraryCommander.modifyLibrary(libraryId = libMurica.id.get, userId = userCaptain.id.get,
          name = Some("MURICA #1!!!!!"), slug = Some("murica_#1"))
        mod2.isRight === true
        mod2.right.get.name === "MURICA #1!!!!!"
        mod2.right.get.slug === LibrarySlug("murica_#1")

        val mod3 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          visibility = Some(LibraryVisibility.PUBLISHED))
        mod3.isRight === true
        mod3.right.get.visibility === LibraryVisibility.PUBLISHED

        val mod4 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userHulk.id.get,
          name = Some("HULK SMASH"))
        mod4.isRight === false
        val mod5 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          name = Some(""))
        mod5.isRight === false

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.name) === Seq("Avengers Missions", "MURICA #1!!!!!", "Science & Stuff")
          allLibs.map(_.slug.value) === Seq("avengers", "murica_#1", "science")
          allLibs.map(_.description) === Seq(Some("Samuel L. Jackson was here"), None, None)
          allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.PUBLISHED, LibraryVisibility.PUBLISHED)
        }
      }
    }

    "remove library, memberships & invites" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps()
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
          libraryMembershipRepo.count === 6
          libraryInviteRepo.count === 4
        }

        val libraryCommander = inject[LibraryCommander]

        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val keeps = db.readOnlyMaster { implicit s => keepRepo.getKeepsFromLibrarySince(t1.minusYears(10), libMurica.id.get, 10000) }
        keeps.size === 3

        libraryCommander.removeLibrary(libMurica.id.get, userCaptain.id.get)
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all.filter(_.state == LibraryStates.ACTIVE)
          allLibs.length === 2
          allLibs.map(_.slug.value) === Seq("avengers", "science")
          libraryMembershipRepo.all.filter(_.state == LibraryMembershipStates.INACTIVE).length === 3
          libraryInviteRepo.all.filter(_.state == LibraryInviteStates.INACTIVE).length === 3
        }

        db.readWrite { implicit s =>
          val deleted = libraryRepo.get(libMurica.id.get)
          deleted.name !== libMurica.name
          deleted.description === None
          deleted.state === LibraryStates.INACTIVE
          deleted.slug !== libMurica.slug

          keeps foreach { keep =>
            val deletedKeep = keepRepo.get(keep.id.get)
            deletedKeep.title === None
          }
        }

        libraryCommander.removeLibrary(libScience.id.get, userIron.id.get)
        libraryCommander.removeLibrary(libShield.id.get, userAgent.id.get)
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all.filter(_.state == LibraryStates.ACTIVE)
          allLibs.length === 0
          allLibs.map(_.slug.value) === Seq.empty
          libraryMembershipRepo.all.filter(_.state == LibraryMembershipStates.INACTIVE).length === 6
          libraryInviteRepo.all.filter(_.state == LibraryInviteStates.INACTIVE).length === 4
        }
      }
    }

    "get libraries by user (which libs am I following / contributing to?)" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites

        db.readOnlyMaster { implicit s =>
          val libraryCommander = inject[LibraryCommander]
          val targetLib1 = libraryCommander.getLibrariesByUser(userIron.id.get)
          val targetLib2 = libraryCommander.getLibrariesByUser(userCaptain.id.get)
          val targetLib3 = libraryCommander.getLibrariesByUser(userAgent.id.get)
          val targetLib4 = libraryCommander.getLibrariesByUser(userHulk.id.get)

          val (ironMemberships, ironLibs) = targetLib1._1.unzip
          ironLibs.map(_.slug.value) === Seq("science", "murica")
          ironMemberships.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)

          val (captainMemberships, captainLibs) = targetLib2._1.unzip
          captainLibs.map(_.slug.value) === Seq("murica")
          captainMemberships.map(_.access) === Seq(LibraryAccess.OWNER)

          val (agentMemberships, agentLibs) = targetLib3._1.unzip
          agentLibs.map(_.slug.value) === Seq("avengers", "murica")
          agentMemberships.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)

          val (hulkMemberships, hulkLibs) = targetLib4._1.unzip
          hulkLibs.map(_.slug.value) === Seq("science")
          hulkMemberships.map(_.access) === Seq(LibraryAccess.READ_INSERT)
          val (hulkInvites, hulkInvitedLibs) = targetLib4._2.unzip
          hulkInvitedLibs.map(_.slug.value) === Seq("murica")
          hulkInvites.map(_.access) === Seq(LibraryAccess.READ_ONLY)
        }
      }
    }

    "does user have visibility" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
        val libraryCommander = inject[LibraryCommander]

        val libShwarmas = db.readWrite { implicit s =>
          val libShwarmas = libraryRepo.save(Library(name = "NY Shwarmas", ownerId = userIron.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1, slug = LibrarySlug("shwarma")))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libShwarmas.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libShwarmas
        }

        libraryCommander.userAccess(userIron.id.get, libScience.id.get, None) === Some(LibraryAccess.OWNER) // test owner access
        libraryCommander.userAccess(userHulk.id.get, libScience.id.get, None) === Some(LibraryAccess.READ_INSERT) // test membership accesss
        libraryCommander.userAccess(userIron.id.get, libShield.id.get, None) === None // test no membership (secret library)
        libraryCommander.userAccess(userHulk.id.get, libMurica.id.get, None) === Some(LibraryAccess.READ_ONLY) // test invited (but not accepted) access
        libraryCommander.userAccess(userCaptain.id.get, libShwarmas.id.get, None) === Some(LibraryAccess.READ_ONLY) // test no membership (public library)

        libraryCommander.userAccess(userCaptain.id.get, libScience.id.get, None) === None // test  library (no membership)
        libraryCommander.userAccess(userCaptain.id.get, libScience.id.get, Some(libScience.universalLink)) === Some(LibraryAccess.READ_ONLY) // test  library (no membership) but with universalLink
      }
    }

    "can user view library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries
        val libraryCommander = inject[LibraryCommander]

        val userWidow = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Natalia", lastName = "Romanova", username = Username("blackwidow"), normalizedUsername = "bar", primaryEmail = Some(EmailAddress("blackwidow@shield.com"))))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = user.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))
          user
        }
        // test can view (permission denied)
        libraryCommander.canViewLibrary(Some(userWidow.id.get), libScience) === false

        // test can view if library is published
        libraryCommander.canViewLibrary(Some(userWidow.id.get), libMurica) === true

        // test can view if user has membership
        libraryCommander.canViewLibrary(Some(userWidow.id.get), libShield) === true
        libraryCommander.canViewLibrary(Some(userWidow.id.get), libScience) === false

        db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, ownerId = userIron.id.get, userId = userWidow.id, access = LibraryAccess.READ_ONLY,
            authToken = "token", passPhrase = "blarg bob fred"))
        }
        // test can view if user has invite
        libraryCommander.canViewLibrary(Some(userWidow.id.get), libScience) === true

        // test can view if non-user provides correct authtoken & passphrase
        libraryCommander.canViewLibrary(None, libScience) === false
        libraryCommander.canViewLibrary(None, libScience, authToken = Some("token"),
          passPhrase = Some(HashedPassPhrase.generateHashedPhrase("wrong one"))) === false
        libraryCommander.canViewLibrary(None, libScience, authToken = Some("token"),
          passPhrase = Some(HashedPassPhrase.generateHashedPhrase("Blarg bobfRed"))) === true
      }
    }

    "intern user system libraries" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]

        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        db.readOnlyMaster { implicit session =>
          val all = libraryRepo.all()
          all.size === 3

          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 1
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 1
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)
        libraryCommander.internSystemGeneratedLibraries(userCaptain.id.get)

        // System libraries are created
        db.readOnlyMaster { implicit session =>
          libraryRepo.all().size === 7
          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 3
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 3
          libraryRepo.getByUser(userHulk.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 0
        }

        // Operation is idempotent
        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)
        libraryCommander.internSystemGeneratedLibraries(userHulk.id.get)
        db.readWrite { implicit session =>
          libraryRepo.all().size === 9
          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 3
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 3
          libraryRepo.getByUser(userHulk.id.get).map(_._2).count(_.ownerId == userHulk.id.get) === 2

          val ironSysLibs = libraryRepo.getByUser(userIron.id.get).map(_._2).filter(_.ownerId == userIron.id.get)
          val main = ironSysLibs.find(_.kind == LibraryKind.SYSTEM_MAIN).get
          val secret = ironSysLibs.find(_.kind == LibraryKind.SYSTEM_SECRET).get

          main.state === LibraryStates.ACTIVE
          main.name === "Main Library"
          main.slug === LibrarySlug("main")
          main.id.get === Id[Library](4)
          secret.state === LibraryStates.ACTIVE

          libraryRepo.save(main.copy(state = LibraryStates.INACTIVE, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("main_old")))
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)

        // Fixes problems in sys libraries
        db.readOnlyMaster { implicit session =>
          val ironMain = libraryRepo.getByUser(userIron.id.get).map(_._2).filter(_.ownerId == userIron.id.get).find(_.kind == LibraryKind.SYSTEM_MAIN).get

          ironMain.state === LibraryStates.ACTIVE
          ironMain.visibility === LibraryVisibility.DISCOVERABLE
        }

        // Removes dupes
        db.readWrite { implicit session =>
          val lib = libraryRepo.save(Library(ownerId = userIron.id.get, name = "Main 2!", kind = LibraryKind.SYSTEM_MAIN, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("main2"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = userIron.id.get, libraryId = lib.id.get, access = LibraryAccess.OWNER, showInSearch = true))
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)

        db.readOnlyMaster { implicit session =>
          val ironMains = libraryRepo.getByUser(userIron.id.get, None).map(_._2).filter(_.ownerId == userIron.id.get).filter(_.kind == LibraryKind.SYSTEM_MAIN)
          ironMains.size === 1
          ironMains.count(l => l.state == LibraryStates.ACTIVE) === 1
          libraryRepo.all.count(l => l.ownerId == userIron.id.get && l.state == LibraryStates.INACTIVE) === 1
        }

      }
    }

    "invite users" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]

        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries
        val libraryCommander = inject[LibraryCommander]

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 0
        }

        val thorEmail = EmailAddress("thorishere@gmail.com")
        val inviteList1 = Seq(
          (Left(userIron.id.get), LibraryAccess.READ_ONLY, None),
          (Left(userAgent.id.get), LibraryAccess.READ_ONLY, None),
          (Left(userHulk.id.get), LibraryAccess.READ_ONLY, None),
          (Right(thorEmail), LibraryAccess.READ_ONLY, Some("America > Asgard")))
        val res1 = libraryCommander.inviteUsersToLibrary(libMurica.id.get, userCaptain.id.get, inviteList1)(HeimdalContext(Map()))
        res1.isRight === true
        res1.right.get === Seq((Left(userIron.externalId), LibraryAccess.READ_ONLY),
          (Left(userAgent.externalId), LibraryAccess.READ_ONLY),
          (Left(userHulk.externalId), LibraryAccess.READ_ONLY),
          (Right(thorEmail), LibraryAccess.READ_ONLY))

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 4
          libraryInviteRepo.all.map(x => (x.userId, x.access)) ===
            Seq((Some(userIron.id.get), LibraryAccess.READ_ONLY),
              (Some(userAgent.id.get), LibraryAccess.READ_ONLY),
              (Some(userHulk.id.get), LibraryAccess.READ_ONLY),
              (None, LibraryAccess.READ_ONLY))
        }

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 4
        }

        val inviteList2_RW = Seq((Left(userIron.id.get), LibraryAccess.READ_WRITE, None))
        val inviteList2_RO = Seq((Left(userIron.id.get), LibraryAccess.READ_ONLY, None))
        // Scumbag Ironman tries to invite himself for READ_ONLY access (OK for Published Library)
        libraryCommander.inviteUsersToLibrary(libMurica.id.get, userIron.id.get, inviteList2_RO)(HeimdalContext(Map())).isRight === true

        // Scumbag Ironman tries to invite himself for READ_WRITE access (NOT OK for Published Library)
        libraryCommander.inviteUsersToLibrary(libMurica.id.get, userIron.id.get, inviteList2_RW)(HeimdalContext(Map())).isRight === true
      }
    }

    "let users join or decline library invites" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
        val libraryCommander = inject[LibraryCommander]

        val t1 = new DateTime(2014, 8, 1, 3, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, ownerId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_WRITE, createdAt = t1))
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, ownerId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
        }

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 6
        }
        libraryCommander.joinLibrary(userIron.id.get, libMurica.id.get).right.get.name === libMurica.name // Ironman accepts invite to 'Murica'
        libraryCommander.joinLibrary(userAgent.id.get, libMurica.id.get).right.get.name === libMurica.name // Agent accepts invite to 'Murica'
        libraryCommander.declineLibrary(userHulk.id.get, libMurica.id.get) // Hulk declines invite to 'Murica'
        libraryCommander.joinLibrary(userHulk.id.get, libScience.id.get).right.get.name === libScience.name // Hulk accepts invite to 'Science' (READ_INSERT) but gets READ_WRITE access

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 6
          val res = for (inv <- libraryInviteRepo.all) yield {
            (inv.libraryId, inv.userId.get, inv.access, inv.state)
          }
          res === Seq(
            (libMurica.id.get, userIron.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED),
            (libMurica.id.get, userAgent.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED),
            (libMurica.id.get, userHulk.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.DECLINED),
            (libScience.id.get, userHulk.id.get, LibraryAccess.READ_INSERT, LibraryInviteStates.ACCEPTED),
            (libScience.id.get, userHulk.id.get, LibraryAccess.READ_WRITE, LibraryInviteStates.ACCEPTED),
            (libScience.id.get, userHulk.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED)
          )
          libraryMembershipRepo.count === 6
          libraryRepo.get(libMurica.id.get).memberCount === 3 //owner + Ironman + Agent
          libraryRepo.get(libScience.id.get).memberCount === 2 //owner + Hulk
          libraryRepo.get(libShield.id.get).memberCount === 1 //owner
        }
      }
    }

    "let users leave library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
        val libraryCommander = inject[LibraryCommander]

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.all.count(x => x.state == LibraryMembershipStates.INACTIVE) === 0
          libraryRepo.get(libMurica.id.get).memberCount === 3
        }

        libraryCommander.leaveLibrary(libMurica.id.get, userAgent.id.get).isRight === true

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.count === 6
          libraryMembershipRepo.all.count(x => x.state == LibraryMembershipStates.INACTIVE) === 1
          libraryRepo.get(libMurica.id.get).memberCount === 2
        }
      }
    }

    "count keeps in library with getKeepsFromLibrariesSince" in {
      withDb(modules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps
        db.readOnlyMaster { implicit s =>
          keepRepo.getKeepsFromLibrarySince(t1.minusYears(10), libShield.id.get, 10000).size === 0
          keepRepo.getKeepsFromLibrarySince(t1.minusYears(10), libMurica.id.get, 10000).size === 3
          keepRepo.getKeepsFromLibrarySince(t1.plusMinutes(10), libMurica.id.get, 10000).size === 2
          keepRepo.getKeepsFromLibrarySince(t1.plusMinutes(20), libMurica.id.get, 10000).size === 1
          keepRepo.getKeepsFromLibrarySince(t1.plusMinutes(60), libMurica.id.get, 10000).size === 0
          keepRepo.getKeepsFromLibrarySince(t1.minusYears(10), libMurica.id.get, 2).size === 2
          keepRepo.getKeepsFromLibrarySince(t1.minusYears(10), libMurica.id.get, 1).size === 1
        }
      }
    }

    "copy keeps to another library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps
        val libraryCommander = inject[LibraryCommander]

        // Ironman has read-only to Murica, and read-write to libRW, and owns 2 libraries: libOwn & Science
        val (libRW, libOwn, keepsInMurica) = db.readWrite { implicit s =>
          val libRW = libraryRepo.save(Library(name = "B", slug = LibrarySlug("b"), ownerId = userCaptain.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libRW.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libRW.id.get, userId = userIron.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true))

          val libOwn = libraryRepo.save(Library(name = "C", slug = LibrarySlug("c"), ownerId = userIron.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libOwn.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val keepsInMurica = keepRepo.getByLibrary(libMurica.id.get, 0, 20)
          (libRW, libOwn, keepsInMurica)
        }

        // Ironman copies 3 keeps from Murica to libOwn (tests RO -> RW)
        val copy1 = libraryCommander.copyKeeps(userIron.id.get, libOwn.id.get, keepsInMurica)
        copy1.size === 0
        val keeps2 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20)
        }
        keeps2.map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")

        // Ironman attempts to copy from libOwn to Murica (but only has read_only access to Murica) (tests RW -> RO)
        val copy2 = libraryCommander.copyKeeps(userIron.id.get, libMurica.id.get, keeps2.slice(0, 2))
        copy2.size === 2
        copy2.head._2 === LibraryError.DestPermissionDenied

        // Ironman copies 2 keeps from libOwn to libRW (tests RW -> RW)
        val copy3 = libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keeps2.slice(0, 2))
        copy3.size === 0

        val keeps3 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 8
          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          keepRepo.getByLibrary(libRW.id.get, 0, 20)
        }
        keeps3.map(_.title.get) === Seq("Freedom", "Reddit")
        db.readWrite { implicit s =>
          keepRepo.save(keeps3(1).copy(state = KeepStates.INACTIVE)) // Reddit is now inactive keep
        }

        // Ironman copies from Murica to libRW (libRW already has existing active URI)
        val copy4 = libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keeps3.slice(0, 1))
        copy4.size === 1
        copy4.head._2 === LibraryError.AlreadyExistsInDest

        // Ironman copies from Murica to libRW (libRW already has existing inactive URI)
        val copy5 = libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keeps3.slice(1, 2))
        copy5.size === 0
        db.readOnlyMaster { implicit s =>
          keepRepo.count === 8
          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          keepRepo.getByLibrary(libRW.id.get, 0, 20).map(_.title.get) === Seq("Freedom", "Reddit")
        }
      }
    }

    "move keeps to another library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps
        val libraryCommander = inject[LibraryCommander]

        // Ironman has read-only to Murica, and read-write to libRW, and owns 2 libraries: libOwn & Science
        val (libRW, libOwn, keepsInMurica) = db.readWrite { implicit s =>
          val libRW = libraryRepo.save(Library(name = "B", slug = LibrarySlug("b"), ownerId = userCaptain.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libRW.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libRW.id.get, userId = userIron.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true))

          val libOwn = libraryRepo.save(Library(name = "C", slug = LibrarySlug("c"), ownerId = userIron.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libOwn.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val keepsInMurica = keepRepo.getByLibrary(libMurica.id.get, 0, 20)
          (libRW, libOwn, keepsInMurica)
        }

        // Ironman attempts to move keeps from Murica to libRW (tests RO -> RW)
        val move1 = libraryCommander.moveKeeps(userIron.id.get, libRW.id.get, keepsInMurica)
        move1.size === 3
        move1.head._2 === LibraryError.SourcePermissionDenied

        libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keepsInMurica)
        val keeps2 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libRW.id.get, 0, 20)
        }
        keeps2.map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")

        // Ironman moves 2 keeps from libRW to libOwn (tests RW -> RW)
        val move2 = libraryCommander.moveKeeps(userIron.id.get, libOwn.id.get, keeps2.slice(0, 2))
        move2.size === 0

        val keeps3 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libRW.id.get, 0, 20).map(_.title.get) === Seq("McDonalds")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20)
        }
        keeps3.map(_.title.get) === Seq("Reddit", "Freedom")

        // Ironman attempts to move keeps from libOwn to Murica (tests RW -> RO)
        val move3 = libraryCommander.moveKeeps(userIron.id.get, libMurica.id.get, keeps3)
        move3.size === 2

        // prepare to test moving duplicates
        libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keepsInMurica)
        db.readWrite { implicit s =>
          val copiedKeeps = keepRepo.getByLibrary(libRW.id.get, 0, 20)
          keepRepo.save(copiedKeeps(1).copy(state = KeepStates.INACTIVE)) // Freedom is now inactive keep

          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20).map(_.title.get) === Seq("Reddit", "Freedom")
          keepRepo.getByLibrary(libRW.id.get, 0, 20).map(_.title.get) === Seq("Reddit", "McDonalds")
        }

        // Ironman moves from libOwn to libRW (libRW already has existing active URI)
        val move5 = libraryCommander.moveKeeps(userIron.id.get, libRW.id.get, keeps3.slice(0, 1)) // move Reddit from libOwn to libRW (already has Reddit)
        move5.size === 1
        move5.head._2 === LibraryError.AlreadyExistsInDest

        // Ironman moves from libOwn to libRW (libRW already has existing inactive URI)
        val move6 = libraryCommander.moveKeeps(userIron.id.get, libRW.id.get, keeps3.slice(1, 2)) // move Freedom from libOwn to libRW (Freedom is inactive)
        move6.size === 0
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20).map(_.title.get) === Seq()
          keepRepo.getByLibrary(libRW.id.get, 0, 20).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
        }
      }
    }

    "create library from tag" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val site1 = "http://www.reddit.com/r/murica"
        val site2 = "http://www.freedom.org/"
        val site3 = "http://www.mcdonalds.com/"

        val (tag1, tag2, libUSA, k1, k2, k3) = db.readWrite { implicit s =>
          val libUSA = libraryRepo.save(Library(name = "USA", slug = LibrarySlug("usa"), ownerId = userCaptain.id.get, visibility = LibraryVisibility.DISCOVERABLE, kind = LibraryKind.USER_CREATED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libUSA.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          // libMurica has 3 keeps
          val keep1 = keepRepo.save(Keep(title = Some("Reddit"), userId = userCaptain.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = libMurica.visibility, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
          val keep2 = keepRepo.save(Keep(title = Some("Freedom"), userId = userCaptain.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = libMurica.visibility, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
          val keep3 = keepRepo.save(Keep(title = Some("McDonalds"), userId = userCaptain.id.get, url = url3.url, urlId = url3.id.get,
            uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = libMurica.visibility, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))

          val tag1 = collectionRepo.save(Collection(userId = userCaptain.id.get, name = Hashtag("tag1")))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tag1.id.get))

          val tag2 = collectionRepo.save(Collection(userId = userCaptain.id.get, name = Hashtag("tag2")))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tag2.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = tag2.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep3.id.get, collectionId = tag2.id.get))

          collectionRepo.count(userCaptain.id.get) === 2
          keepRepo.count === 3
          keepToCollectionRepo.count === 4
          keepToCollectionRepo.getByCollection(tag1.id.get).length === 1
          keepToCollectionRepo.getByCollection(tag2.id.get).length === 3
          (tag1, tag2, libUSA, keep1, keep2, keep3)
        }

        val libraryCommander = inject[LibraryCommander]

        //copy 1 keep to libUSA - libUSA has no keeps
        val res1 = libraryCommander.copyKeepsFromCollectionToLibrary(userCaptain.id.get, libUSA.id.get, Hashtag("tag1"))
        res1.isRight === true
        res1.right.get.length === 0 // all successes
        db.readOnlyMaster { implicit s =>
          keepRepo.count === 4 // 1 new keep added (it was tagged by tag1 & tag2, so two more ktc's added)
          keepToCollectionRepo.count === 6
          keepToCollectionRepo.getByCollection(tag1.id.get).length === 2
          keepToCollectionRepo.getByCollection(tag2.id.get).length === 4
        }

        // unkeep k2
        db.readWrite { implicit s =>
          keepRepo.save(k2.copy(state = KeepStates.INACTIVE))
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 2
        }
        // There should be 4 keeps now with 'tag2' try to copy them into libMurica
        val res2 = libraryCommander.copyKeepsFromCollectionToLibrary(userCaptain.id.get, libMurica.id.get, Hashtag("tag2"))
        res2.isRight === true
        res2.right.get.length === 3 // 1 keep reactivated, 3 failed keeps
        db.readOnlyMaster { implicit s =>
          keepRepo.count === 4
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 3
          keepToCollectionRepo.count === 6
          keepToCollectionRepo.getByCollection(tag1.id.get).length === 2
          keepToCollectionRepo.getByCollection(tag2.id.get).length === 4
        }
      }
    }

    "send library invitation notification & emails" in {
      withDb(modules: _*) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
        val libraryCommander = inject[LibraryCommander]
        val emailRepo = inject[ElectronicMailRepo]
        val eliza = inject[ElizaServiceClient].asInstanceOf[FakeElizaServiceClientImpl]
        eliza.inbox.size === 0

        val allInvites = db.readOnlyMaster { implicit s =>
          libraryInviteRepo.all
        }
        Await.result(libraryCommander.inviteBulkUsers(allInvites), Duration(10, "seconds"))
        eliza.inbox.size === 4
        eliza.inbox.count(t => t._2 == NotificationCategory.User.LIBRARY_INVITATION && t._4.endsWith("/0.jpg")) === 4
        eliza.inbox.count(t => t._3 == "https://www.kifi.com/captainamerica/murica") === 3
        db.readOnlyMaster { implicit s => emailRepo.count === 4 }
      }
    }

  }
}
