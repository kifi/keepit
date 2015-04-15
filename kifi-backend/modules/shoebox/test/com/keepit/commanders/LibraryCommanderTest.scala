package com.keepit.commanders

import com.keepit.common.util.Paginator
import com.keepit.model.UserFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.LibraryInviteFactory._
import com.keepit.model.LibraryInviteFactoryHelper._
import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.abook.model.RichContact
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.{ FakeCryptoModule, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.mail.{ ElectronicMailRepo, EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.{ ImageSize, FakeShoeboxStoreModule }
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, FakeElizaServiceClientImpl, FakeElizaServiceClientModule }
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.social.BasicUser
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike

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

  private val profileLibraryOrdering = new Ordering[Library] {
    def compare(self: Library, that: Library): Int =
      (self.kind compare that.kind) match {
        case 0 =>
          (self.memberCount compare that.memberCount) match {
            case 0 => -(self.id.get.id compare that.id.get.id)
            case c => -c
          }
        case c => c
      }
  }

  def setupUsers()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val emailRepo = inject[UserEmailAddressRepo]
    val emailIron = EmailAddress("tony@stark.com")
    val emailCaptain = EmailAddress("steve.rogers@hotmail.com")
    val emailAgent = EmailAddress("samuelljackson@shield.com")
    val emailHulk = EmailAddress("incrediblehulk@gmail.com")

    val (userIron, userCaptain, userAgent, userHulk) = db.readWrite { implicit s =>
      val userIron = user().withUsername("ironman").saved
      val userCaptain = user().withUsername("captainamerica").saved
      val userAgent = user().withUsername("agentfury").saved
      val userHulk = user().withUsername("incrediblehulk").saved

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
      val libShield = library().withUser(userAgent).withName("Avengers Missions").withSlug("avengers").secret().saved
      val libMurica = library().withUser(userCaptain).withName("MURICA").withSlug("murica").published().saved
      val libScience = library().withUser(userIron).withName("Science & Stuff").withSlug("science").discoverable().saved
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
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))

      // Ironman invites the Hulk to contribute to 'Science & Stuff'
      libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_INSERT, createdAt = t1))

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

      membership().fromLibraryInvite(inv1).saved
      membership().fromLibraryInvite(inv2).saved
      membership().fromLibraryInvite(inv3).saved

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
        uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3),
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
      val keep2 = keepRepo.save(Keep(title = Some("Freedom"), userId = userCaptain.id.get, url = url2.url, urlId = url2.id.get,
        uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(15), keptAt = t1.plusMinutes(15),
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
      val keep3 = keepRepo.save(Keep(title = Some("McDonalds"), userId = userCaptain.id.get, url = url3.url, urlId = url3.id.get,
        uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(30), keptAt = t1.plusMinutes(30),
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

        val lib1Request = LibraryAddRequest(name = "Avengers Missions", slug = "avengers", visibility = LibraryVisibility.SECRET)

        val lib2Request = LibraryAddRequest(name = "MURICA", slug = "murica", visibility = LibraryVisibility.PUBLISHED)

        val lib3Request = LibraryAddRequest(name = "Science and Stuff", slug = "science", visibility = LibraryVisibility.DISCOVERABLE)

        val lib5Request = LibraryAddRequest(name = "Invalid Param", slug = "", visibility = LibraryVisibility.SECRET)

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
          allMemberships.map(_.lastJoinedAt).flatten.size === 3
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
          allLibs.map(_.color.nonEmpty === true)
        }
      }

    }

    "modify library" in {
      withDb(modules: _*) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        val libraryCommander = inject[LibraryCommander]
        val mod1 = libraryCommander.modifyLibrary(libraryId = libShield.id.get, userId = userAgent.id.get,
          LibraryModifyRequest(description = Some("Samuel L. Jackson was here")))
        mod1.isRight === true
        mod1.right.get.description === Some("Samuel L. Jackson was here")

        val mod2 = libraryCommander.modifyLibrary(libraryId = libMurica.id.get, userId = userCaptain.id.get,
          LibraryModifyRequest(name = Some("MURICA #1!!!!!"), slug = Some("murica_#1")))
        mod2.isRight === true
        mod2.right.get.name === "MURICA #1!!!!!"
        mod2.right.get.slug === LibrarySlug("murica_#1")

        val mod3 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          LibraryModifyRequest(visibility = Some(LibraryVisibility.PUBLISHED)))
        mod3.isRight === true
        mod3.right.get.visibility === LibraryVisibility.PUBLISHED

        val mod3NoChange = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          LibraryModifyRequest(visibility = Some(LibraryVisibility.PUBLISHED)))
        mod3NoChange.isRight === true
        mod3NoChange.right.get.visibility === LibraryVisibility.PUBLISHED

        val mod4 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userHulk.id.get,
          LibraryModifyRequest(name = Some("HULK SMASH")))
        mod4.isRight === false
        val mod5 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          LibraryModifyRequest(name = Some("")))
        mod5.isRight === false

        val mod6 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
          LibraryModifyRequest(color = Some(LibraryColor.SKY_BLUE)))
        mod6.isRight === true
        mod6.right.get.color === Some(LibraryColor.SKY_BLUE)

        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.all
          allLibs.length === 3
          allLibs.map(_.name) === Seq("Avengers Missions", "MURICA #1!!!!!", "Science & Stuff")
          allLibs.map(_.slug.value) === Seq("avengers", "murica_#1", "science")
          allLibs.map(_.description) === Seq(Some("Samuel L. Jackson was here"), None, None)
          allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.PUBLISHED, LibraryVisibility.PUBLISHED)
          allLibs.map(_.color) === Seq(None, None, Some(LibraryColor.SKY_BLUE))
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
          libraryMembershipRepo.save(LibraryMembership(libraryId = libShwarmas.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER))
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
          libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = user.id.get, access = LibraryAccess.READ_ONLY))
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
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = userWidow.id, access = LibraryAccess.READ_ONLY,
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
          main.name === Library.SYSTEM_MAIN_DISPLAY_NAME
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
          libraryMembershipRepo.save(LibraryMembership(userId = userIron.id.get, libraryId = lib.id.get, access = LibraryAccess.OWNER))
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

        // Captain America invites everybody to his Published Library
        val inviteList1 = Seq(
          (Left(userIron.id.get), LibraryAccess.READ_ONLY, None),
          (Left(userAgent.id.get), LibraryAccess.READ_ONLY, None),
          (Left(userHulk.id.get), LibraryAccess.READ_ONLY, None),
          (Right(thorEmail), LibraryAccess.READ_ONLY, Some("America > Asgard")))
        val res1 = Await.result(libraryCommander.inviteUsersToLibrary(libMurica.id.get, userCaptain.id.get, inviteList1), Duration(5, "seconds"))

        res1.isRight === true
        res1.right.get === Seq((Left(BasicUser.fromUser(userIron)), LibraryAccess.READ_ONLY),
          (Left(BasicUser.fromUser(userAgent)), LibraryAccess.READ_ONLY),
          (Left(BasicUser.fromUser((userHulk))), LibraryAccess.READ_ONLY),
          (Right(RichContact(thorEmail)), LibraryAccess.READ_ONLY))

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 4
          val allInvites = libraryInviteRepo.all
          allInvites.count(_.access == LibraryAccess.READ_ONLY) === 4
          allInvites.count(_.userId.isDefined) === 3
          allInvites.count(_.emailAddress.isDefined) === 1
        }

        // Agent Nick Fury accepts invite & joins the Library
        libraryCommander.joinLibrary(userAgent.id.get, libMurica.id.get)
        // Tests that users can have multiple invitations multiple times
        // but if invites are sent within 5 Minutes of each other, they do not persist!
        Await.result(libraryCommander.inviteUsersToLibrary(libMurica.id.get, userCaptain.id.get, inviteList1), Duration(5, "seconds")).isRight === true
        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 4 // 8 invites sent, only 4 persisted
        }

        val inviteList2_RW = Seq((Left(userIron.id.get), LibraryAccess.READ_WRITE, None))
        val inviteList2_RO = Seq((Left(userIron.id.get), LibraryAccess.READ_ONLY, None))
        // Scumbag Ironman tries to invite himself for READ_ONLY access (OK for Published Library)
        Await.result(libraryCommander.inviteUsersToLibrary(libMurica.id.get, userIron.id.get, inviteList2_RO), Duration(5, "seconds")).isRight === true

        // Scumbag Ironman tries to invite himself for READ_WRITE access (NOT OK for Published Library)
        Await.result(libraryCommander.inviteUsersToLibrary(libMurica.id.get, userIron.id.get, inviteList2_RW), Duration(5, "seconds")).isRight === true
      }
    }

    "let users join or decline library invites" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]

        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
        val libraryCommander = inject[LibraryCommander]

        val t1 = new DateTime(2014, 8, 1, 3, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_WRITE, createdAt = t1))
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
        }

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 6
        }

        val eliza = inject[ElizaServiceClient].asInstanceOf[FakeElizaServiceClientImpl]
        eliza.inbox.size === 0

        libraryCommander.joinLibrary(userIron.id.get, libMurica.id.get).right.get.name === libMurica.name // Ironman accepts invite to 'Murica'

        //this for some reason only fails on Jenkins (and fails consitently now). Taking it out to uncreak the build.
        // eliza.inbox.size === 1
        // eliza.inbox(0) === (userCaptain.id.get, NotificationCategory.User.LIBRARY_FOLLOWED, "https://www.kifi.com/ironman", s"http://localhost/users/${userIron.externalId}/pics/200/0.jpg")

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

          libraryMembershipRepo.getWithLibraryIdAndUserId(libMurica.id.get, userIron.id.get).get.lastJoinedAt must beSome
          libraryMembershipRepo.getWithLibraryIdAndUserId(libMurica.id.get, userAgent.id.get).get.lastJoinedAt must beSome
          libraryMembershipRepo.getWithLibraryIdAndUserId(libMurica.id.get, userHulk.id.get) must beNone
          libraryMembershipRepo.getWithLibraryIdAndUserId(libScience.id.get, userHulk.id.get).get.lastJoinedAt must beSome
        }

        // Proving that accepting a lesser invite doesn't destroy current access
        db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(libraryId = libShield.id.get, inviterId = userIron.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
        }
        libraryCommander.joinLibrary(userAgent.id.get, libShield.id.get)
        libraryCommander.userAccess(userAgent.id.get, libShield.id.get, None) === Some(LibraryAccess.OWNER)

        // Joining a private library from an email invite (library invite has a null userId field)!
        db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(libraryId = libShield.id.get, inviterId = userAgent.id.get, emailAddress = Some(EmailAddress("incrediblehulk@gmail.com")), access = LibraryAccess.READ_ONLY, authToken = "asdf", passPhrase = "unlock"))
          libraryInviteRepo.getByLibraryIdAndAuthToken(libShield.id.get, "asdf").exists(i => i.state == LibraryInviteStates.ACCEPTED) === false
        }

        val successJoin = libraryCommander.joinLibrary(userHulk.id.get, libShield.id.get, Some("asdf"))
        successJoin.isRight === true
        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.getByLibraryIdAndAuthToken(libShield.id.get, "asdf").exists(i => i.state == LibraryInviteStates.ACCEPTED) === true
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
          libraryMembershipRepo.save(LibraryMembership(libraryId = libRW.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libRW.id.get, userId = userIron.id.get, access = LibraryAccess.READ_WRITE))

          val libOwn = libraryRepo.save(Library(name = "C", slug = LibrarySlug("c"), ownerId = userIron.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libOwn.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER))

          val keepsInMurica = keepRepo.getByLibrary(libMurica.id.get, 0, 20)
          (libRW, libOwn, keepsInMurica)
        }

        // Ironman copies 3 keeps from Murica to libOwn (tests RO -> RW)
        val copy1 = libraryCommander.copyKeeps(userIron.id.get, libOwn.id.get, keepsInMurica, None)
        copy1._1.size === 3
        copy1._2.size === 0
        val keeps2 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20)
        }
        keeps2.map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")

        // Ironman attempts to copy from libOwn to Murica (but only has read_only access to Murica) (tests RW -> RO)
        val copy2 = libraryCommander.copyKeeps(userIron.id.get, libMurica.id.get, keeps2.slice(0, 2), None)
        copy2._1.size === 0
        copy2._2.size === 2
        copy2._2.head._2 === LibraryError.DestPermissionDenied

        // Ironman copies 2 keeps from libOwn to libRW (tests RW -> RW)
        val copy3 = libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keeps2.slice(0, 2), None)
        copy3._1.size === 2
        copy3._2.size === 0

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
        val copy4 = libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keeps3.slice(0, 1), None)
        copy4._1.size === 0
        copy4._2.size === 1
        copy4._2.head._2 === LibraryError.AlreadyExistsInDest

        // Ironman copies from Murica to libRW (libRW already has existing inactive URI)
        val copy5 = libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keeps3.slice(1, 2), None)
        copy5._1.size === 1
        copy5._2.size === 0

        val keeps4 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 8
          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20).map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          val keepsRW = keepRepo.getByLibrary(libRW.id.get, 0, 20)
          keepsRW.map(_.title.get) === Seq("Freedom", "Reddit")
          keepsRW
        }

        // Test copying keep from library without membership
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libShield.id.get, 0, 20).length === 0
        }
        val copy6 = libraryCommander.copyKeeps(userAgent.id.get, libShield.id.get, keeps4, None)
        copy6._1.size === 2
        copy6._2.size === 0
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libShield.id.get, 0, 20).length === 2
        }

        // Test Main & Private Library
        val (mainLib, secretLib) = libraryCommander.internSystemGeneratedLibraries(userIron.id.get)

        // Copy from User Created to Main Library
        libraryCommander.copyKeeps(userIron.id.get, mainLib.id.get, keeps4, None)
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(mainLib.id.get, 0, 20).length === 2
          keepRepo.getPrimaryInDisjointByUriAndUser(keeps4(0).uriId, userIron.id.get).nonEmpty === true
          keepRepo.getPrimaryInDisjointByUriAndUser(keeps4(1).uriId, userIron.id.get).nonEmpty === true
        }
        // Copy from Main to Private Library
        libraryCommander.copyKeeps(userIron.id.get, secretLib.id.get, keeps4, None)
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(mainLib.id.get, 0, 20).length === 0
          keepRepo.getByLibrary(secretLib.id.get, 0, 20).length === 2
          keepRepo.getPrimaryInDisjointByUriAndUser(keeps4(0).uriId, userIron.id.get).nonEmpty === true
          keepRepo.getPrimaryInDisjointByUriAndUser(keeps4(1).uriId, userIron.id.get).nonEmpty === true
        }

        // Copy from Private to User Created Library
        libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keeps4, None)
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(mainLib.id.get, 0, 20).length === 0
          keepRepo.getByLibrary(secretLib.id.get, 0, 20).length === 2
          keepRepo.getByLibrary(libRW.id.get, 0, 20).length === 2
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
          libraryMembershipRepo.save(LibraryMembership(libraryId = libRW.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libRW.id.get, userId = userIron.id.get, access = LibraryAccess.READ_WRITE))

          val libOwn = libraryRepo.save(Library(name = "C", slug = LibrarySlug("c"), ownerId = userIron.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libOwn.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER))

          val keepsInMurica = keepRepo.getByLibrary(libMurica.id.get, 0, 20)
          (libRW, libOwn, keepsInMurica)
        }

        // Ironman attempts to move keeps from Murica to libRW (tests RO -> RW)
        val move1 = libraryCommander.moveKeeps(userIron.id.get, libRW.id.get, keepsInMurica)
        move1._1.size === 0
        move1._2.size === 3
        move1._2.head._2 === LibraryError.SourcePermissionDenied

        // prepare to test moving keeps among libraries with RW access
        libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keepsInMurica, None)
        val keeps2 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libRW.id.get, 0, 20)
        }
        keeps2.map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")

        // Ironman moves 2 keeps from libRW to libOwn (tests RW -> RW)
        val move2 = libraryCommander.moveKeeps(userIron.id.get, libOwn.id.get, keeps2.slice(0, 2))
        move2._1.size === 2
        move2._2.size === 0

        val keeps3 = db.readOnlyMaster { implicit s =>
          keepRepo.count === 6
          keepRepo.getByLibrary(libRW.id.get, 0, 20).map(_.title.get) === Seq("McDonalds")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20)
        }
        keeps3.map(_.title.get) === Seq("Reddit", "Freedom")

        // Ironman attempts to move keeps from libOwn to Murica (tests RW -> RO)
        val move3 = libraryCommander.moveKeeps(userIron.id.get, libMurica.id.get, keeps3)
        move3._1.size === 0
        move3._2.size === 2

        // prepare to test moving duplicates
        libraryCommander.copyKeeps(userIron.id.get, libRW.id.get, keepsInMurica, None)
        db.readWrite { implicit s =>
          val copiedKeeps = keepRepo.getByLibrary(libRW.id.get, 0, 20)
          keepRepo.save(copiedKeeps(1).copy(state = KeepStates.INACTIVE)) // Freedom is now inactive keep

          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20).map(_.title.get) === Seq("Reddit", "Freedom")
          keepRepo.getByLibrary(libRW.id.get, 0, 20).map(_.title.get) === Seq("Reddit", "McDonalds")
        }

        // Ironman moves from libOwn to libRW (libRW already has existing active URI)
        val move5 = libraryCommander.moveKeeps(userIron.id.get, libRW.id.get, keeps3.slice(0, 1)) // move Reddit from libOwn to libRW (already has Reddit)
        move5._1.size === 0
        move5._2.size === 1
        move5._2.head._2 === LibraryError.AlreadyExistsInDest

        // Ironman moves from libOwn to libRW (libRW already has existing inactive URI)
        val move6 = libraryCommander.moveKeeps(userIron.id.get, libRW.id.get, keeps3.slice(1, 2)) // move Freedom from libOwn to libRW (Freedom is inactive)
        move6._1.size === 1
        move6._2.size === 0
        val keeps4 = db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libMurica.id.get, 0, 20).map(_.title.get) === Seq("McDonalds", "Freedom", "Reddit")
          keepRepo.getByLibrary(libOwn.id.get, 0, 20).map(_.title.get) === Seq()
          val keeps4 = keepRepo.getByLibrary(libRW.id.get, 0, 20)
          keeps4.map(_.title.get) === Seq("Reddit", "Freedom", "McDonalds")
          keeps4
        }

        // Test Main & Private Library
        val (mainLib, secretLib) = libraryCommander.internSystemGeneratedLibraries(userIron.id.get)

        // Move from User Created to Main Library
        libraryCommander.moveKeeps(userIron.id.get, mainLib.id.get, keeps4)
        val keeps5 = db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(mainLib.id.get, 0, 20)
        }
        keeps5.length === 3

        // Move from Main to Secret
        libraryCommander.moveKeeps(userIron.id.get, secretLib.id.get, keeps5)
        val keeps6 = db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(mainLib.id.get, 0, 20).length === 0
          keepRepo.getByLibrary(secretLib.id.get, 0, 20)
        }
        keeps6.length === 3

        // Move from Secret to User Created
        libraryCommander.moveKeeps(userIron.id.get, libRW.id.get, keeps6)
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(mainLib.id.get, 0, 20).length === 0
          keepRepo.getByLibrary(secretLib.id.get, 0, 20).length === 0
          keepRepo.getByLibrary(libRW.id.get, 0, 20).length === 3
        }
      }
    }

    "copy keeps between libraries from tag" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val site1 = "http://www.reddit.com/r/murica"
        val site2 = "http://www.freedom.org/"
        val site3 = "http://www.mcdonalds.com/"

        val (tag1, tag2, libUSA, k1, k2, k3) = db.readWrite { implicit s =>
          val libUSA = libraryRepo.save(Library(name = "USA", slug = LibrarySlug("usa"), ownerId = userCaptain.id.get, visibility = LibraryVisibility.DISCOVERABLE, kind = LibraryKind.USER_CREATED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libUSA.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER))

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          // libMurica has 3 keeps
          val keep1 = keepRepo.save(Keep(title = Some("Reddit"), userId = userCaptain.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3),
            visibility = libMurica.visibility, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
          val keep2 = keepRepo.save(Keep(title = Some("Freedom"), userId = userCaptain.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3),
            visibility = libMurica.visibility, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
          val keep3 = keepRepo.save(Keep(title = Some("McDonalds"), userId = userCaptain.id.get, url = url3.url, urlId = url3.id.get,
            uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3),
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
        res1.right.get._1.length === 1 // all successes
        res1.right.get._2.length === 0 // all successes
        db.readOnlyMaster { implicit s =>
          keepRepo.count === 4 // 1 new keep added (it was tagged by tag1 & tag2, so two more ktc's added)
          keepToCollectionRepo.count === 6
          keepToCollectionRepo.getByCollection(tag1.id.get).length === 2
          keepToCollectionRepo.getByCollection(tag2.id.get).length === 4
        }

        // unkeep k1
        db.readWrite { implicit s =>
          keepRepo.save(k1.copy(state = KeepStates.INACTIVE))
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 2
          keepToCollectionRepo.getKeepsForTag(tag2.id.get).length === 3
        }
        // There should be 3 active keeps now with 'tag2' try to copy them into libMurica
        val res2 = libraryCommander.copyKeepsFromCollectionToLibrary(userCaptain.id.get, libMurica.id.get, Hashtag("tag2"))
        res2.isRight === true
        res2.right.get._1.length === 1 // 1 reactivated keep
        res2.right.get._2.length === 2 // 2 failed keeps
        db.readOnlyMaster { implicit s =>
          keepRepo.count === 4
          keepRepo.getByLibrary(libUSA.id.get, 0, 10).length === 1
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 3
        }
      }
    }

    "move keeps between libraries from tag" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val site1 = "http://www.reddit.com/r/murica"
        val site2 = "http://www.freedom.org/"
        val site3 = "http://www.mcdonalds.com/"

        val (tag1, tag2, libUSA, k1, k2, k3) = db.readWrite { implicit s =>
          val libUSA = libraryRepo.save(Library(name = "USA", slug = LibrarySlug("usa"), ownerId = userCaptain.id.get, visibility = LibraryVisibility.DISCOVERABLE, kind = LibraryKind.USER_CREATED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libUSA.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER))

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

        //move 1 keep to libUSA - libUSA has no keeps
        val res1 = libraryCommander.moveKeepsFromCollectionToLibrary(userCaptain.id.get, libUSA.id.get, Hashtag("tag1"))
        res1.right.get._1.length === 1 // all successes
        res1.right.get._2.length === 0
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libUSA.id.get, 0, 10).length === 1
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 2
        }

        //move 1 keep to libUSA - libUSA has no keeps
        val res2 = libraryCommander.moveKeepsFromCollectionToLibrary(userCaptain.id.get, libMurica.id.get, Hashtag("tag1"))
        res2.right.get._1.length === 1 // all successes
        res2.right.get._2.length === 0
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libUSA.id.get, 0, 10).length === 0
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 3
        }

        // move duplicate keeps to its own library
        val res3 = libraryCommander.moveKeepsFromCollectionToLibrary(userCaptain.id.get, libMurica.id.get, Hashtag("tag1"))
        res3.right.get._1.length === 0
        res3.right.get._2.length === 1 // already kept
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libUSA.id.get, 0, 10).length === 0
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 3
        }

        // move in bulk
        val res4 = libraryCommander.moveKeepsFromCollectionToLibrary(userCaptain.id.get, libUSA.id.get, Hashtag("tag2"))
        res4.right.get._1.length === 3
        res4.right.get._2.length === 0
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libUSA.id.get, 0, 10).length === 3
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 0
        }

        // keep URI in libMurica to test for duplicates -> now 4 keeps with 'tag2'
        libraryCommander.copyKeeps(userCaptain.id.get, libMurica.id.get, Seq(k2), None)

        val res5 = libraryCommander.moveKeepsFromCollectionToLibrary(userCaptain.id.get, libMurica.id.get, Hashtag("tag2"))
        res5.right.get._1.length === 2
        res5.right.get._2.length === 2 // already kept
        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(libUSA.id.get, 0, 10).length === 0
          keepRepo.getByLibrary(libMurica.id.get, 0, 10).length === 3
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

        val t1 = new DateTime(2014, 8, 1, 7, 0, 0, 1, DEFAULT_DATE_TIME_ZONE)
        val newInvites = Seq(
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1),
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1),
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1),
          LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_INSERT, createdAt = t1)
        )

        Await.result(libraryCommander.processInvites(newInvites), Duration(10, "seconds"))
        eliza.inbox.size === 4
        println(eliza.inbox)
        eliza.inbox.count(t => t._2 == NotificationCategory.User.LIBRARY_FOLLOWED && t._4.endsWith("/0.jpg")) === 0
        eliza.inbox.count(t => t._2 == NotificationCategory.User.LIBRARY_INVITATION && t._4.endsWith("/0.jpg")) === 4
        eliza.inbox.count(t => t._3 == "https://www.kifi.com/captainamerica/murica") === 3
        db.readOnlyMaster { implicit s => emailRepo.count === 4 }

        val t2 = t1.plusMinutes(3) // send another set of invites 3 minutes later - too spammy, should not persist!
        val newInvitesAgain = Seq(
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t2),
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t2),
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t2),
          LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_INSERT, createdAt = t2)
        )
        Await.result(libraryCommander.processInvites(newInvitesAgain), Duration(10, "seconds"))
        eliza.inbox.size === 4
        db.readOnlyMaster { implicit s => emailRepo.count === 4 }
      }
    }

    "get library members" in {
      withDb(modules: _*) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
        db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, emailAddress = Some(EmailAddress("thor@asgard.com")), access = LibraryAccess.READ_ONLY))
          val memberCounts = libraryMembershipRepo.countWithLibraryIdByAccess(libMurica.id.get)
          memberCounts.owner === 1
          memberCounts.readOnly === 2
          memberCounts.readInsert === 0
          memberCounts.readWrite === 0
        }
        val libraryCommander = inject[LibraryCommander]
        val members = libraryCommander.getLibraryMembers(libMurica.id.get, 0, 10, true)
        // collaborators
        members._1.map(_.userId) === Seq()
        // followers
        members._2.map(_.userId).toSet === Set(userAgent.id.get, userIron.id.get)
        // invitees
        members._3.map(t => (t._1)) === Seq(Left(userHulk.id.get), Right(EmailAddress("thor@asgard.com")))
      }
    }

    "updates last email sent for a list of keeps" in {
      withDb(modules: _*) { implicit injector =>
        val factory = inject[ShoeboxTestFactory]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience, keeps: Seq[Keep]) = factory.setupLibraryKeeps()
        val libraryCommander = inject[LibraryCommander]

        libraryCommander.updateLastEmailSent(userIron.id.get, keeps)

        db.readWrite { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(libShield.id.get, userIron.id.get).map(_.lastEmailSent) must beSome
          libraryMembershipRepo.getWithLibraryIdAndUserId(libScience.id.get, userIron.id.get).map(_.lastEmailSent) must beSome
          libraryMembershipRepo.getWithLibraryIdAndUserId(libMurica.id.get, userCaptain.id.get).get.lastEmailSent must beNone
        }
      }
    }

    "get invited to libraries" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]
        val (user1, user2, user3, libs) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved
          val lib1 = libraries(2).map(_.published().withUser(user1)).saved.head.savedInvitation(user2)
          val lib2 = libraries(2).map(_.secret().withUser(user1)).saved.head.savedInvitation(user2).savedInvitation(user3)
          val lib3 = libraries(2).map(_.published().withUser(user2)).saved.head.savedInvitation(user1)
          invite().fromLibraryOwner(library.published().withUser(user2).saved).saved.savedStateChange(LibraryInviteStates.ACCEPTED)
          invite().fromLibraryOwner(library.published().withUser(user2).saved).declined.saved
          libraries(10).map(_.published().withUser(user1)).saved
          libraries(10).map(_.published().withUser(user2)).saved
          (user1, user2, user3, lib1 :: lib2 :: lib3 :: Nil)
        }

        libraryCommander.getInvitedLibraries(user1, None, Paginator(0, 5), ImageSize("100x100")).size === 0
        libraryCommander.getInvitedLibraries(user1, Some(user2), Paginator(0, 5), ImageSize("100x100")).size === 0

        val libs1 = libraryCommander.getInvitedLibraries(user1, Some(user1), Paginator(0, 5), ImageSize("100x100"))
        libs1.size === 1
        libs1.map(_.id).head === Library.publicId(libs(2).id.get)

        val libs2 = libraryCommander.getInvitedLibraries(user2, Some(user2), Paginator(0, 5), ImageSize("100x100"))
        libs2.size === 2
        libs2.map(_.id) === libs.take(2).reverse.map(_.id.get).map(Library.publicId)

        val libs3 = libraryCommander.getInvitedLibraries(user3, Some(user3), Paginator(0, 5), ImageSize("100x100"))
        libs3.size === 1
        libs3.map(_.id).head === Library.publicId(libs(1).id.get)

      }
    }

    "getFollowingLibraries for anonymous and paginate" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]
        val (owner, other, allLibs) = db.readWrite { implicit s =>
          val owner = user().saved
          val other = user().saved
          val otherFollows1 = libraries(2).map(_.published().withUser(owner)).saved.head.savedFollowerMembership(other)
          val otherFollows2 = libraries(2).map(_.secret().withUser(owner)).saved.head.savedFollowerMembership(other)
          libraries(2).map(_.published().withUser(other)).saved.head.savedFollowerMembership(owner)
          val otherFollows3 = libraries(10).map(_.published().withUser(owner)).saved.map(_.savedFollowerMembership(other))
          libraries(10).map(_.published().withUser(other)).saved
          (owner, other, List(otherFollows1) ++ otherFollows3)
        }

        val libsP1 = libraryCommander.getFollowingLibraries(other, None, Paginator(0, 5), ImageSize("100x100"))
        libsP1.size === 5
        libsP1.map(_.id) === allLibs.reverse.take(5).map(_.id.get).map(Library.publicId)

        val libsP2 = libraryCommander.getFollowingLibraries(other, None, Paginator(1, 5), ImageSize("100x100"))
        libsP2.size === 5
        libsP2.map(_.id) === allLibs.reverse.drop(5).take(5).map(_.id.get).map(Library.publicId)

        val libsP3 = libraryCommander.getFollowingLibraries(other, None, Paginator(2, 5), ImageSize("100x100"))
        libsP3.size === 1
        libsP3.map(_.id) === allLibs.reverse.drop(10).take(5).map(_.id.get).map(Library.publicId)
      }
    }

    "getFollowingLibraries for self" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]
        val (owner, other, allLibs) = db.readWrite { implicit s =>
          val owner = user().saved
          val other = user().saved
          val otherFollows1 = libraries(2).map(_.published().withUser(owner)).saved.head.savedFollowerMembership(other)
          val otherFollows2 = libraries(2).map(_.secret().withUser(owner)).saved.head.savedFollowerMembership(other)
          libraries(2).map(_.published().withUser(other)).saved.head.savedFollowerMembership(owner)
          val otherFollows3 = libraries(10).map(_.published().withUser(owner)).saved.map(_.savedFollowerMembership(other))
          libraries(10).map(_.published().withUser(other)).saved
          (owner, other, List(otherFollows1, otherFollows2) ++ otherFollows3)
        }

        val libs = libraryCommander.getFollowingLibraries(other, Some(other), Paginator(0, 500), ImageSize("100x100"))
        libs.size === 12
        libs.head.owner.externalId === owner.externalId
        libs.map(_.id) === allLibs.reverse.map(_.id.get).map(Library.publicId)
      }
    }

    "getFollowingLibraries for other" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]
        val (user1, user2, user3, follows1, follows2, follows3, follows4) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved
          val follows1 = libraries(2).map(_.published().withUser(user1).withName("a")).saved.head.savedFollowerMembership(user2)
          val follows2 = libraries(2).map(_.secret().withUser(user1).withName("b")).saved.head.savedFollowerMembership(user2).savedFollowerMembership(user3)
          val follows3 = libraries(10).map(_.secret().withUser(user3).withName("c")).saved.map(_.savedFollowerMembership(user2))
          libraries(2).map(_.published().withUser(user2).withName("d")).saved.head.savedFollowerMembership(user1)
          val follows4 = libraries(10).map(_.published().withUser(user1).withName("e")).saved.map(_.savedFollowerMembership(user2))
          libraries(10).map(_.published().withUser(user2).withName("f")).saved
          (user1, user2, user3, follows1, follows2, follows3, follows4)
        }

        val self = libraryCommander.getFollowingLibraries(user2, Some(user2), Paginator(0, 500), ImageSize("100x100"))
        self.size === 22
        self.map(_.id) === (List(follows1, follows2) ++ follows3 ++ follows4).reverse.map(_.id.get).map(Library.publicId)

        val libs1 = libraryCommander.getFollowingLibraries(user2, Some(user1), Paginator(0, 500), ImageSize("100x100"))
        libs1.size === 12
        libs1.map(_.id) === (List(follows1, follows2) ++ follows4).reverse.map(_.id.get).map(Library.publicId)
      }
    }

    "sortFollowers" in {
      withDb(modules: _*) { implicit injector =>
        val libraryCommander = inject[LibraryCommander]
        val user1 = BasicUser.fromUser(user().get)
        val user2 = BasicUser.fromUser(user().withPictureName("a").get)
        val user3 = BasicUser.fromUser(user().get)
        val user4 = BasicUser.fromUser(user().withPictureName("g").get)
        val user5 = BasicUser.fromUser(user().withPictureName("b").get)
        val sorted = libraryCommander.sortUsersByImage(user1 :: user2 :: user3 :: user4 :: user5 :: Nil)
        sorted.map(_.pictureName) === (user2 :: user4 :: user5 :: user1 :: user3 :: Nil).map(_.pictureName)
      }
    }

    "get ownerLibraries for friend" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]
        val (owner, other, friend, allLibs) = db.readWrite { implicit s =>
          val owner = user().saved
          val other = user().saved
          val friend = user().saved
          val ownerLibs1 = libraries(2).map(_.published().withUser(owner)).saved
          library.secret().withUser(owner).saved
          val ownerPrivLib = library.secret().withUser(owner).saved.savedFollowerMembership(friend)
          libraries(2).map(_.published().withUser(other)).saved
          libraries(2).map(_.secret().withUser(other)).saved
          val ownerLibs2 = libraries(10).map(_.published().withUser(owner)).saved
          libraries(10).map(_.published().withUser(other)).saved

          libraryRepo.all.map { lib =>
            keeps(2).map(_.withLibrary(lib)).saved
          }
          (owner, other, friend, ownerLibs1 ++ List(ownerPrivLib) ++ ownerLibs2)
        }

        libraryCommander.getOwnProfileLibraries(owner, None, Paginator(0, 1000), ImageSize("100x100")).size === 12

        val libsForOther = libraryCommander.getOwnProfileLibraries(owner, Some(other), Paginator(0, 1000), ImageSize("100x100"))
        libsForOther.size === 12

        val libsForFriend = libraryCommander.getOwnProfileLibraries(owner, Some(friend), Paginator(0, 1000), ImageSize("100x100"))
        libsForFriend.size === 13
        libsForFriend.map(_.id) === allLibs.reverse.map(_.id.get).map(Library.publicId)
      }
    }

    "get ownerLibraries for self" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]
        val (owner, other, allLibs) = db.readWrite { implicit s =>
          val owner = user().saved
          val other = user().saved
          val ownerLibs1 = libraries(2).map(_.published().withUser(owner)).saved
          val ownerPrivLib = library.secret().withUser(owner).saved
          libraries(2).map(_.published().withUser(other)).saved
          libraries(2).map(_.secret().withUser(other)).saved
          val ownerLibs2 = libraries(10).map(_.published().withUser(owner)).saved
          libraries(10).map(_.published().withUser(other)).saved

          libraryRepo.all.take(4).map { lib =>
            keeps(2).map(_.withLibrary(lib)).saved
          }

          (owner, other, ownerLibs1 ++ List(ownerPrivLib) ++ ownerLibs2)
        }

        // 4 libs with keeps. First two are private, next two should be seen by anonymous.
        libraryCommander.getOwnProfileLibraries(owner, None, Paginator(0, 1000), ImageSize("100x100")).size === 2

        val libsForOther = libraryCommander.getOwnProfileLibraries(owner, Some(other), Paginator(0, 1000), ImageSize("100x100"))
        libsForOther.size === 2

        val libsForFriend = libraryCommander.getOwnProfileLibraries(owner, Some(owner), Paginator(0, 1000), ImageSize("100x100"))
        libsForFriend.size === 3
      }
    }

    "get ownerLibraries for anonymous and paginate" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]
        val (owner, allLibs) = db.readWrite { implicit s =>
          val owner = user().saved
          val other = user().saved
          val ownerLibs1 = libraries(2).map(_.published().withUser(owner)).saved
          libraries(2).map(_.secret().withUser(owner)).saved
          libraries(2).map(_.published().withUser(other)).saved
          val ownerLibs2 = libraries(10).map(_.published().withUser(owner).withMemberCount(6)).saved
          libraries(10).map(_.published().withUser(other).withMemberCount(6)).saved
          val ownerLibs3 = libraries(3).map(_.published().withUser(owner)).saved
          libraries(3).map(_.published().withUser(other)).saved

          libraryRepo.all.map { lib =>
            keeps(2).map(_.withLibrary(lib)).saved
          }

          (owner, ownerLibs1 ++ ownerLibs2 ++ ownerLibs3)
        }

        val ord = profileLibraryOrdering

        val libsP1 = libraryCommander.getOwnProfileLibraries(owner, None, Paginator(0, 5), ImageSize("100x100"))
        libsP1.size === 5
        libsP1.map(_.id) === allLibs.sorted(ord).take(5).map(_.id.get).map(Library.publicId)

        val libsP2 = libraryCommander.getOwnProfileLibraries(owner, None, Paginator(1, 5), ImageSize("100x100"))
        libsP2.size === 5
        libsP2.map(_.id) === allLibs.sorted(ord).drop(5).take(5).map(_.id.get).map(Library.publicId)

        val libsP3 = libraryCommander.getOwnProfileLibraries(owner, None, Paginator(2, 5), ImageSize("100x100"))
        libsP3.size === 5
        libsP3.map(_.id) === allLibs.sorted(ord).drop(10).take(5).map(_.id.get).map(Library.publicId)
      }
    }

    "get owner library counts" in {
      withDb(modules: _*) { implicit injector =>
        val libraryCommander = inject[LibraryCommander]
        db.readWrite { implicit s =>
          val libs = libraries(5)
          val user1 = libs.take(3).map { _.withUser(Id[User](1)).published() }.saved
          val user2 = libs.drop(3).map { _.withUser(Id[User](2)).published() }.saved
        }

        db.readOnlyMaster { implicit s =>
          val users = Set(1, 2, 100).map { Id[User](_) }
          val map = libraryRepo.getOwnerLibraryCounts(users)
          map === Map(Id[User](1) -> 3, Id[User](2) -> 2, Id[User](100) -> 0)

          val map2 = libraryRepo.getOwnerLibraryCounts(Set[Id[User]]())
          map2.size === 0
        }

      }
    }
  }
}
