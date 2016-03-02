package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class SlackPushingActorTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule(),
    FakeClockModule()
  )

  "SlackPushingActor" should {
    "do magic parsing on look-heres" in {
      val goodUrls = Set(
        "http://i.imgur.com/Lg9iNlB.gifv",
        "https://cdn-images-1.medium.com/max/1600/1*U1EoH6ltIYQ6KDSfJbR9fQ.png",
        "http://www.iflscience.com/sites/www.iflscience.com/files/styles/ifls_large/public/blog/%5Bnid%5D/embed_asset.jpg?itok=t0LiDC4J"
      )
      val badUrls = Set(
        "http://google.com",
        "http://www.wsj.com/articles/donald-trump-poised-for-string-of-super-tuesday-wins-amid-gop-split-1456830163"
      )
      goodUrls.foreach { str => SlackPushingActor.imageUrlRegex.findFirstIn(str) must beSome }
      badUrls.foreach { str => SlackPushingActor.imageUrlRegex.findFirstIn(str) must beNone }
      1 === 1
    }
  }
}
