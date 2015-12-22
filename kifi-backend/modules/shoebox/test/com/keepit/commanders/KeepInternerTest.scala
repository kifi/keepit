package com.keepit.commanders

import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.healthcheck._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test._
import net.codingwell.scalaguice.ScalaModule
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

class KeepInternerTest extends Specification with ShoeboxTestInjector {

  implicit val context = HeimdalContext.empty

  def modules: Seq[ScalaModule] = Seq(
    FakeKeepImportsModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  val keep42 = Json.obj("url" -> "http://42go.com", "isPrivate" -> false)
  val keepKifi = Json.obj("url" -> "http://kifi.com", "isPrivate" -> false)
  val keepGoog = Json.obj("url" -> "http://google.com", "isPrivate" -> false)
  val keepBing = Json.obj("url" -> "http://bing.com", "isPrivate" -> false)
  val keepStanford = Json.obj("url" -> "http://stanford.edu", "isPrivate" -> false)
  val keepApple = Json.obj("url" -> "http://apple.com", "isPrivate" -> false)

  "BookmarkInterner" should {

    "persist keep" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
        }
        val (main, secret) = inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[KeepInterner]
        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmarks(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        )), user.id.get, secret, KeepSource.email)
        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 1
          keepRepo.get(bookmarks.head.id.get).copy(updatedAt = bookmarks.head.updatedAt) === bookmarks.head
          keepRepo.all.size === 1
        }
      }
    }

    "persist to RawKeepRepo" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[KeepInterner]
        bookmarkInterner.persistRawKeeps(inject[RawKeepFactory].toRawKeep(user.id.get, KeepSource.bookmarkImport, Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), libraryId = None))
        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          rawKeepRepo.all.headOption.map(_.url) === Some("http://42go.com")
          rawKeepRepo.all.size === 1
        }
      }
    }
    "persist to RawKeepRepo (with libraryId)" in {
      withDb(modules: _*) { implicit injector =>
        val (user, lib) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
          val lib = library().saved
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
          (user, lib)
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[KeepInterner]
        bookmarkInterner.persistRawKeeps(inject[RawKeepFactory].toRawKeep(user.id.get, KeepSource.bookmarkImport, Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), libraryId = lib.id))
        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          val targetRawKeep = rawKeepRepo.all.headOption
          targetRawKeep.map(_.url) === Some("http://42go.com")
          targetRawKeep.flatMap(_.libraryId) === lib.id
          rawKeepRepo.all.size === 1
        }
      }
    }

    "persist bookmarks" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
        }
        val (library, _) = inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[KeepInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), keepKifi))
        val deduped = bookmarkInterner.deDuplicate(raw)
        deduped === raw
        deduped.size === 2
        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(raw, user.id.get, library, KeepSource.email)

        db.readWrite { implicit session =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 2
          keepRepo.all.size === 2
        }
      }
    }

    "dedup on" in {
      withDb(modules: _*) { implicit injector =>
        val bookmarkInterner = inject[KeepInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), keep42))
        bookmarkInterner.deDuplicate(raw).size === 1
      }
    }
    "dedup off" in {
      withDb(modules: _*) { implicit injector =>
        val bookmarkInterner = inject[KeepInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://43go.com",
          "isPrivate" -> true
        ), keep42))
        val deduped: Seq[RawBookmarkRepresentation] = bookmarkInterner.deDuplicate(raw)
        deduped.size === 2
      }
    }
    "persist bookmarks with one bad url" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
        }
        val (library, _) = inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val fakeAirbrake = inject[FakeAirbrakeNotifier]
        fakeAirbrake.errorCount() === 0
        val bookmarkInterner = inject[KeepInterner]
        val raw = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), Json.obj(
          "url" -> ("http://kifi.com/" + List.fill(300)("this_is_a_very_long_url/").mkString),
          "isPrivate" -> false
        ), Json.obj(
          "url" -> "http://kifi.com",
          "isPrivate" -> true
        )))

        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(raw, user.id.get, library, KeepSource.email)
        fakeAirbrake.errorCount() === 2
        bookmarks.size === 2
        db.readWrite { implicit session =>
          keepRepo.all.size === 2
          keepRepo.all.map(_.url).toSet === Set[String](
            "http://42go.com",
            "http://kifi.com")
        }
      }
    }
    "properly intern keeps for the same url" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit s =>
          UserFactory.user().withName("Greg", "Smith").withUsername("test").saved
        }
        val (library, _) = inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)
        val bookmarkInterner = inject[KeepInterner]
        val (initialBookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        ))), user.id.get, library, KeepSource.keeper)
        initialBookmarks.size === 1

        val (bookmarks, _) = bookmarkInterner.internRawBookmarks(inject[RawBookmarkFactory].toRawBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com/",
          "isPrivate" -> true
        ))), user.id.get, library, KeepSource.keeper)
        db.readOnlyMaster { implicit s =>
          bookmarks.size === 1
          keepRepo.all.size === 1
        }
      }
    }
  }

}
