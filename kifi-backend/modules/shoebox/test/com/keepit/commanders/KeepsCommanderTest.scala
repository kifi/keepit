package com.keepit.commanders

import com.keepit.heimdal.HeimdalContext
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.common.db.Id
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
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
    FakeCuratorServiceClientModule() ::
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
          userExperimentRepo.save(UserExperiment(experimentType = ExperimentType.FAKE, userId = user2.id.get))
          (user1.id.get, user2.id.get)
        }

        val now = currentDateTime
        val seq1 = Seq(LimitedAugmentationInfo(None, Seq((real, now)), 0, 0, Seq((Id[Library](1), real, now)), 0, 0, Seq.empty, 0),
          LimitedAugmentationInfo(None, Seq((real, now)), 0, 0, Seq((Id[Library](2), real, now)), 0, 0, Seq.empty, 0),
          LimitedAugmentationInfo(None, Seq(), 0, 0, Seq(), 0, 0, Seq.empty, 0))
        inject[KeepDecorator].filterLibraries(seq1) === seq1
        val seq2 = Seq(LimitedAugmentationInfo(None, Seq((real, now)), 0, 0, Seq((Id[Library](1), real, now)), 0, 0, Seq.empty, 0),
          LimitedAugmentationInfo(None, Seq((real, now), (fake, now)), 0, 0, Seq((Id[Library](2), real, now), (Id[Library](3), fake, now)), 0, 0, Seq.empty, 0),
          LimitedAugmentationInfo(None, Seq(), 0, 0, Seq((Id[Library](3), fake, now)), 0, 0, Seq.empty, 0))
        inject[KeepDecorator].filterLibraries(seq2) === seq1
      }
    }

    "export keeps" in {
      withDb() { implicit injector =>
        val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val site1 = "http://www.google.com/"
        val site2 = "http://www.amazon.com/"
        val site3 = "http://www.kifi.com/"

        val user = db.readWrite { implicit s =>

          val user1 = UserFactory.user().saved

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Amazon")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("Kifi")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          val keep1 = keepRepo.save(Keep(title = Some("k1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          keepRepo.save(Keep(title = Some("k2"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(9), keptAt = t1.plusMinutes(9),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          keepRepo.save(Keep(title = Some("k3"), userId = user1.id.get, url = url3.url, urlId = url3.id.get,
            uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(6), keptAt = t1.plusMinutes(6),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint, state = KeepStates.INACTIVE))

          val col1 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("t1")))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = col1.id.get))

          user1
        }

        val keepExports = db.readOnlyMaster { implicit s => keepRepo.getKeepExports(user.id.get) }
        keepExports.length === 2
        keepExports(0) === KeepExport(title = Some("k2"), createdAt = t1.plusMinutes(9), url = site2)
        keepExports(1) === KeepExport(title = Some("k1"), createdAt = t1.plusMinutes(3), url = site1, tags = Some("t1"))
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

        val result = inject[KeepsCommander].assembleKeepExport(keepExports)

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
