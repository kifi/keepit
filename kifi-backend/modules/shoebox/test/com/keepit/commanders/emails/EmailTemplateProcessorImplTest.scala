package com.keepit.commanders.emails

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers._
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.{ Library, LibrarySlug, LibraryVisibility, NotificationCategory }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.twirl.api.Html

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class EmailTemplateProcessorImplTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    ProdShoeboxServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule()
  )

  "EmailTemplateProcessor" should {
    "replaces placeholders with real values" in {
      withDb(modules: _*) { implicit injector =>
        val testFactory = inject[ShoeboxTestFactory]
        val (user1, user2, user3, user4) = db.readWrite { implicit rw =>
          testFactory.createUsers()
        }

        val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val library = db.readWrite { implicit rw =>
          libraryRepo.save(Library(name = "Avengers Missions", slug = LibrarySlug("avengers"),
            visibility = LibraryVisibility.SECRET, ownerId = user1.id.get, createdAt = t1, memberCount = 1))
        }

        val (id1, id2, id3, id4) = (user1.id.get, user2.id.get, user3.id.get, user4.id.get)

        val html1 = Html(s"""
          |${firstName(id1)} ${lastName(id1)} and ${fullName(id2)} joined!
          |<img src="${avatarUrl(id1)}" alt="${fullName(id1)}"/>
          |<img src="${avatarUrl(id2)}" alt="${fullName(id2)}"/>
          |<img src="${avatarUrl(id3)}" alt="${fullName(id3)}"/>
          |<img src="${avatarUrl(id4)}" alt="${fullName(id4)}"/>
          |Join my library: ${libraryName(library.id.get)}
          |liburl: ${libraryUrl(library.id.get, "")}
          |<a href="$unsubscribeUrl">Unsubscribe Me</a>
          |<a href="${unsubscribeUrl(id3)}">Unsubscribe User</a>
          |<a href="${unsubscribeUrl(user3.primaryEmail.get)}">Unsubscribe Email</a>
        """.stripMargin)

        val text1 = Html(
          s"""
          |${firstName(id2)} ${lastName(id2)} and ${fullName(id1)} joined!
          |Join my library: ${libraryName(library.id.get)}
          |liburl: ${libraryUrl(library.id.get, "")}
          |${avatarUrl(id3)}
          |unsub1 $unsubscribeUrl
          |unsub2 ${unsubscribeUrl(id3)}
          |unsub3 ${unsubscribeUrl(user3.primaryEmail.get)}
           """.stripMargin)

        val processor = inject[EmailTemplateProcessorImpl]
        val emailToSend = EmailToSend(
          title = "Test Email!!!",
          to = Right(SystemEmailAddress.JOSH),
          cc = Seq(SystemEmailAddress.ENG),
          from = SystemEmailAddress.NOTIFICATIONS,
          fromName = Some(Right(firstName(id2) + "!!!")),
          subject = "hi " + firstName(id1) + " and " + firstName(id2),
          category = NotificationCategory.User.SOCIAL_FRIEND_JOINED,
          htmlTemplate = html1,
          textTemplate = Some(text1)
        )

        val outputF = processor.process(emailToSend)
        val processed = Await.result(outputF, Duration(5, "seconds"))

        processed.subject === "hi Aaron and Bryan"
        processed.fromName === Some("Bryan!!!")

        val output = processed.htmlBody.value
        output must contain("privacy?utm_source=aboutFriends&amp;utm_medium=email&amp;utm_campaign=socialFriendJoined&amp;utm_content=footerPrivacy")
        output must contain("<title>Test Email!!!</title>")
        output must contain("Aaron Paul and Bryan Cranston joined!")
        output must contain("Join my library: Avengers Missions")
        output must contain("liburl: http://dev.ezkeep.com:9000/test/avengers")
        output must contain("""<img src="https://cloudfront/users/1/pics/100/0.jpg" alt="Aaron Paul"/>""")
        output must contain("""<img src="https://cloudfront/users/2/pics/100/0.jpg" alt="Bryan Cranston"/>""")
        output must contain("""<img src="https://cloudfront/users/3/pics/100/0.jpg" alt="Anna Gunn"/>""")
        output must contain("""<img src="https://cloudfront/users/4/pics/100/0.jpg" alt="Dean Norris"/>""")

        val text = processed.textBody.get.value
        text must contain("Bryan Cranston and Aaron Paul joined!")
        text must contain("Join my library: Avengers Missions")
        text must contain("liburl: http://dev.ezkeep.com:9000/test/avengers")
        text must contain("https://cloudfront/users/3/pics/100/0.jpg")
        text must contain("unsub1 https://www.kifi.com/unsubscribe/")
        text must contain("unsub2 https://www.kifi.com/unsubscribe/")
        text must contain("unsub3 https://www.kifi.com/unsubscribe/")
        text must contain("Unsubscribe here: https://www.kifi.com/unsubscribe/")
        text must contain("Kifi.com | 709 N Shoreline Blvd, Mountain View, CA 94043, USA")

      }
    }
  }
}
