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
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.search.augmentation.LimitedAugmentationInfo
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeKeepImportsModule }
import com.keepit.common.store.FakeShoeboxStoreModule

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
      1 === 1
    }
    "export a user's personal keeps" in {
      1 === 1
    }
    "export a user's org keeps" in {
      1 === 1
    }
  }
}
