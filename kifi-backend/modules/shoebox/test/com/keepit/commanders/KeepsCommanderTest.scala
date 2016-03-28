package com.keepit.commanders

import com.keepit.heimdal.HeimdalContext
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.common.db.Id
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.search.augmentation.LimitedAugmentationInfo
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeKeepImportsModule }
import com.keepit.common.store.FakeShoeboxStoreModule

class KeepsCommanderTest extends Specification with ShoeboxTestInjector {

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

  "KeepsCommander" should {

    "filter fake users and libs" in {
      withDb(modules: _*) { implicit injector =>
        inject[KeepDecorator].filterLibraries(Seq()) === Seq()
        val (real, fake) = db.readWrite { implicit s =>
          val user1 = user().saved
          val user2 = user().saved
          userExperimentRepo.save(UserExperiment(experimentType = UserExperimentType.FAKE, userId = user2.id.get))
          (user1.id.get, user2.id.get)
        }

        val now = currentDateTime
        val seq1 = Seq(LimitedAugmentationInfo(None, Seq.empty, 0, Seq((real, now)), 0, 0, Seq((Id[Library](1), real, now)), 0, 0, Seq.empty, 0),
          LimitedAugmentationInfo(None, Seq.empty, 0, Seq((real, now)), 0, 0, Seq((Id[Library](2), real, now)), 0, 0, Seq.empty, 0),
          LimitedAugmentationInfo(None, Seq.empty, 0, Seq(), 0, 0, Seq(), 0, 0, Seq.empty, 0))
        inject[KeepDecorator].filterLibraries(seq1) === seq1
        val seq2 = Seq(LimitedAugmentationInfo(None, Seq.empty, 0, Seq((real, now)), 0, 0, Seq((Id[Library](1), real, now)), 0, 0, Seq.empty, 0),
          LimitedAugmentationInfo(None, Seq.empty, 0, Seq((real, now), (fake, now)), 0, 0, Seq((Id[Library](2), real, now), (Id[Library](3), fake, now)), 0, 0, Seq.empty, 0),
          LimitedAugmentationInfo(None, Seq.empty, 0, Seq(), 0, 0, Seq((Id[Library](3), fake, now)), 0, 0, Seq.empty, 0))
        inject[KeepDecorator].filterLibraries(seq2) === seq1
      }
    }

    "export keeps" in {
      withDb() { implicit injector =>
        val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val site1 = "http://www.google.com/"
        val site2 = "http://www.amazon.com/"
        val site3 = "http://www.kifi.com/"

        val (user, keeps) = db.readWrite { implicit s =>

          val user1 = UserFactory.user().saved

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Amazon")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("Kifi")))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          val keep1 = KeepFactory.keep().withTitle("k1").withUser(user1).withUri(uri1).withLibrary(lib1).saved
          val keep2 = KeepFactory.keep().withTitle("k2").withUser(user1).withUri(uri2).withLibrary(lib1).saved
          val deletedKeep = KeepFactory.keep().withTitle("k2").withUser(user1).withUri(uri3).withLibrary(lib1).saved
          keepRepo.deactivate(deletedKeep)

          val col1 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("t1")))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = col1.id.get))

          (user1, Seq(keep1, keep2, deletedKeep))
        }

        val keepExports = db.readOnlyMaster { implicit s => keepRepo.getKeepExports(user.id.get) }
        keepExports.length === 2
        keepExports(0) === KeepExport(title = keeps(1).title, createdAt = keeps(1).keptAt, url = keeps(1).url)
        keepExports(1) === KeepExport(title = keeps(0).title, createdAt = keeps(0).keptAt, url = keeps(0).url, tags = Some("t1"))
      }
    }

    "assemble keep exports" in {
      withDb(modules: _*) { implicit injector =>
        val dateTime0 = DateTime.now
        val dateTime1 = DateTime.now
        val dateTime2 = DateTime.now
        val seconds0 = dateTime0.getMillis() / 1000
        val seconds1 = dateTime1.getMillis() / 1000
        val seconds2 = dateTime2.getMillis() / 1000

        val keepExports =
          KeepExport(createdAt = dateTime0, title = Some("title 1&1"), url = "http://www.hi.com11", tags = Some("tagA")) ::
            KeepExport(createdAt = dateTime1, title = Some("title 21"), url = "http://www.hi.com21", tags = Some("tagA,tagB&tagC")) ::
            KeepExport(createdAt = dateTime2, title = Some("title 31"), url = "http://www.hi.com31", tags = None) ::
            Nil

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
}
