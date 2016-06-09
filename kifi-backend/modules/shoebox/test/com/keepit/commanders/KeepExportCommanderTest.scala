package com.keepit.commanders

import java.util.zip.{ ZipEntry, ZipOutputStream }

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.core.optionExtensionOps
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.math3.random.MersenneTwister
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class KeepExportCommanderTest extends Specification with ShoeboxTestInjector {
  def modules = FakeKeepImportsModule() ::
    FakeExecutionContextModule() ::
    FakeShoeboxStoreModule() ::
    FakeSearchServiceClientModule() ::
    FakeCortexServiceClientModule() ::
    FakeShoeboxServiceModule() ::
    FakeUserActionsModule() ::
    FakeABookServiceClientModule() ::
    FakeSocialGraphModule() ::
    Nil

  implicit val context = HeimdalContext.empty
  "KeepExportCommander" should {
    def setup()(implicit injector: Injector): (User, Organization, Seq[Library], Seq[Keep]) = db.readWrite { implicit session =>
      val owner = UserFactory.user().saved
      val user = UserFactory.user().saved
      val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(user)).saved
      val libs = Seq(
        LibraryFactory.library().withOwner(owner).withCollaborators(Seq(user)).withOrganization(org).withVisibility(LibraryVisibility.PUBLISHED).saved,
        LibraryFactory.library().withOwner(owner).withCollaborators(Seq(user)).withOrganization(org).saved,
        LibraryFactory.library().withOwner(owner).withCollaborators(Seq(user)).saved,
        LibraryFactory.library().withOwner(user).withCollaborators(Seq(owner)).withOrganization(org).withVisibility(LibraryVisibility.SECRET).saved,
        LibraryFactory.library().withOwner(user).withCollaborators(Seq(owner)).withOrganization(org).saved,
        LibraryFactory.library().withOwner(user).withCollaborators(Seq(owner)).saved
      )
      val keeps = libs.flatMap { lib =>
        Seq(
          KeepFactory.keep().withUser(owner).withLibrary(lib).saved,
          KeepFactory.keep().withUser(user).withLibrary(lib).saved
        )
      }
      (owner, org, libs, keeps)
    }
    "handle old code that really ought to be deprecated" in {
      "assemble keep exports" in {
        withDb(modules: _*) { implicit injector =>
          val dateTime0 = DateTime.now
          val dateTime1 = DateTime.now
          val dateTime2 = DateTime.now
          val seconds0 = dateTime0.getMillis() / 1000
          val seconds1 = dateTime1.getMillis() / 1000
          val seconds2 = dateTime2.getMillis() / 1000

          val keepExports = List(
            KeepExport(createdAt = dateTime0, title = Some("title 1&1"), url = "http://www.hi.com11", tags = Some("tagA")),
            KeepExport(createdAt = dateTime1, title = Some("title 21"), url = "http://www.hi.com21", tags = Some("tagA,tagB&tagC")),
            KeepExport(createdAt = dateTime2, title = Some("title 31"), url = "http://www.hi.com31", tags = None)
          )

          val result = inject[KeepExportCommander].assembleKeepExport(keepExports)
          val expected = s"""<!DOCTYPE NETSCAPE-Bookmark-file-1>
                            |<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
                            |<!--This is an automatically generated file.
                            |It will be read and overwritten.
                            |Do Not Edit! -->
                            |<Title>Kifi Bookmarks Export</Title>
                            |<H1>Bookmarks</H1>
                            |<DL>
                            |<DT><A HREF="http://www.hi.com11" ADD_DATE="$seconds0" TAGS="tagA">title 1&amp;1</A>
                            |<DT><A HREF="http://www.hi.com21" ADD_DATE="$seconds1" TAGS="tagA,tagB&amp;tagC">title 21</A>
                            |<DT><A HREF="http://www.hi.com31" ADD_DATE="$seconds2">title 31</A>
                            |</DL>""".stripMargin
          result must equalTo(expected)
        }
      }
    }
    "format exports correctly" in {
      def sampleExport()(implicit injector: Injector): KeepExportResponse = db.readWrite { implicit session =>
        val date = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val user = UserFactory.user().saved
        val lib = LibraryFactory.library().withOwner(user).saved
        val keeps = Seq(
          KeepFactory.keep().withLibrary(lib).withTitle("title 1").withKeptAt(date).withUrl("http://www.url1.com").withNote("note 1").saved,
          KeepFactory.keep().withLibrary(lib).withTitle("title 2").withKeptAt(date).withUrl("http://www.url2.com").withNote("note 2").saved
        )
        val keepTags = Map(
          keeps(0).id.get -> Seq("1a", "1b"),
          keeps(1).id.get -> Seq("2a")
        )
        val keepLibs = Map(
          keeps(0).id.get -> Seq(lib),
          keeps(1).id.get -> Seq(lib)
        )
        KeepExportResponse(keeps, keepTags, keepLibs)
      }

      "for html exports" in {
        withDb(modules: _*) { implicit injector =>
          val export = sampleExport()
          val libName = export.keepLibs.values.flatten.head.name
          export.formatAsHtml === s"""<!DOCTYPE NETSCAPE-Bookmark-file-1>
                                      |<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
                                      |<!--This is an automatically generated file.
                                      |It will be read and overwritten.
                                      |Do Not Edit! -->
                                      |<Title>Kifi Bookmarks Export</Title>
                                      |<H1>Bookmarks</H1>
                                      |<DL>
                                      |<DT><A HREF="http://www.url1.com" ADD_DATE="1404475200" TAGS="1a,1b,$libName">title 1</A>
                                      |<DT><A HREF="http://www.url2.com" ADD_DATE="1404475200" TAGS="2a,$libName">title 2</A>
                                      |</DL>""".stripMargin
        }
      }
      "for json exports" in {
        withDb(modules: _*) { implicit injector =>
          val export = sampleExport()
          val libName = export.keepLibs.values.flatten.head.name
          export.formatAsJson === Json.obj("keeps" -> Json.arr(
            Json.obj("title" -> "title 1", "date" -> 1404475200, "url" -> "http://www.url1.com", "source" -> "keeper", "note" -> "note 1", "tags" -> Json.arr("1a", "1b"), "libraries" -> Json.arr(libName)),
            Json.obj("title" -> "title 2", "date" -> 1404475200, "url" -> "http://www.url2.com", "source" -> "keeper", "note" -> "note 2", "tags" -> Json.arr("2a"), "libraries" -> Json.arr(libName))
          ))
        }
      }
    }
    "export a user's personal keeps" in {
      "get the correct keeps" in {
        withDb(modules: _*) { implicit injector =>
          val (user, org, libs, keeps) = setup()
          val exportedKeeps = db.readOnlyMaster { implicit session =>
            keepExportCommander.unsafeExportKeeps(PersonalKeepExportRequest(user.id.get)).keeps.toList.toSeq
          }

          val personalLibs = libs.filter(_.space == UserSpace(user.id.get)).map(_.id.get).toSet
          val expected = keeps.filter(k => k.userId.safely.contains(user.id.get) && k.recipients.libraries.exists(personalLibs.contains)).sortBy(_.keptAt).map(_.id.get)

          val actual = exportedKeeps.map(_.id.get)

          actual === expected
        }
      }
    }
    "export a user's org keeps" in {
      "get the correct keeps" in {
        withDb(modules: _*) { implicit injector =>
          val (user, org, libs, keeps) = setup()
          val exportedKeeps = db.readOnlyMaster { implicit session =>
            keepExportCommander.unsafeExportKeeps(OrganizationKeepExportRequest(user.id.get, Set(org.id.get))).keeps.toList.toSeq
          }

          val orgLibs = libs.filter(lib => lib.space == OrganizationSpace(org.id.get) && lib.visibility != LibraryVisibility.SECRET).map(_.id.get).toSet
          val expected = keeps.filter(k => k.recipients.libraries.exists(orgLibs.contains)).sortBy(_.keptAt).map(_.id.get)

          val actual = exportedKeeps.map(_.id.get)

          actual === expected
        }
      }
    }
  }
}
