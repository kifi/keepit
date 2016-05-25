package com.keepit.commanders.emails

import java.net.URLEncoder

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers._
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.twirl.api.Html

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class EmailTemplateProcessorImplTest extends Specification with ShoeboxTestInjector {
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    ProdShoeboxServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeCortexServiceClientModule()
  )

  "EmailTemplateProcessor" should {
    "replaces placeholders with real values" in {
      "pass josh's huge test" in {
        withDb(modules: _*) { implicit injector =>
          val testFactory = inject[ShoeboxTestFactory]
          val (user1, user2, user3, user4) = testFactory.setupUsers()

          val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
          val (library, keep) = db.readWrite { implicit rw =>
            val library = libraryRepo.save(Library(name = "Avengers Missions", slug = LibrarySlug("avengers"),
              visibility = LibraryVisibility.SECRET, ownerId = user1.id.get, createdAt = t1, memberCount = 1))
            val uri = uriRepo.save(NormalizedURI.withHash("http://www.avengers.org/", Some("Avengers")))
            val keep = KeepFactory.keep().withTitle("Avengers$1.org").withUser(user1).withUri(uri).withLibrary(library).saved
            (library, keep)
          }

          val (id1, id2, id3, id4) = (user1.id.get, user2.id.get, user3.id.get, user4.id.get)

          val emailAddress3 = db.readOnlyMaster { implicit session =>
            userEmailAddressRepo.getByUser(id3)
          }

          val html1 = Html(s"""
            |${firstName(id1)} ${lastName(id1)} and ${fullName(id2)} joined!
            |<img src="${avatarUrl(id1)}" alt="${fullName(id1)}"/>
            |<img src="${avatarUrl(id2)}" alt="${fullName(id2)}"/>
            |<img src="${avatarUrl(id3)}" alt="${fullName(id3)}"/>
            |<img src="${avatarUrl(id4)}" alt="${fullName(id4)}"/>
            |${textWithHashtag("this is a [#test] tag!")}
            |Join my library: ${libraryName(library.id.get)}
            |liburl: ${libraryUrl(library.id.get, "")}
            |Look at this keep: ${keepName(keep.id.get)}
            |keepurl: ${keepUrl(keep.id.get, "")}
            |<a href="$unsubscribeUrl">Unsubscribe Me</a>
            |<a href="${unsubscribeUrl(id3)}">Unsubscribe User</a>
            |<a href="${unsubscribeUrl(emailAddress3)}">Unsubscribe Email</a>
          """.stripMargin)

          val text1 = Html(
            s"""
            |${firstName(id2)} ${lastName(id2)} and ${fullName(id1)} joined!
            |Join my library: ${libraryName(library.id.get)}
            |liburl: ${libraryUrl(library.id.get, "")}
            |Look at this keep: ${keepName(keep.id.get)}
            |keepurl: ${keepUrl(keep.id.get, "")}          |
            |${avatarUrl(id3)}
            |unsub1 $unsubscribeUrl
            |unsub2 ${unsubscribeUrl(id3)}
            |unsub3 ${unsubscribeUrl(emailAddress3)}
             """.stripMargin)

          val processor = inject[EmailTemplateProcessorImpl]
          val emailToSend = EmailToSend(
            title = "Test Email!!!",
            to = Right(SystemEmailAddress.JOSH),
            cc = Seq(SystemEmailAddress.ENG42),
            from = SystemEmailAddress.NOTIFICATIONS,
            fromName = Some(Right(firstName(id2) + "!!!")),
            subject = "hi " + firstName(id1) + " and " + firstName(id2),
            category = NotificationCategory.User.SOCIAL_FRIEND_JOINED,
            htmlTemplate = html1,
            textTemplate = Some(text1)
          )

          val outputF = processor.process(emailToSend)
          val processed = Await.result(outputF, Duration(5, "seconds"))

          processed.subject === "hi Tony and Steve"
          processed.fromName === Some("Steve!!!")

          val output = processed.htmlBody.value
          output must contain("privacy?utm_source=aboutFriends&amp;utm_medium=email&amp;utm_campaign=socialFriendJoined&amp;utm_content=footerPrivacy")
          output must contain("<title>Test Email!!!</title>")
          output must contain("Tony Stark and Steve Rogers joined!")
          output must contain("Join my library: Avengers Missions")
          output must contain("liburl: http://dev.ezkeep.com:9000/ironman/avengers")
          output must contain("Look at this keep: Avengers$1.org")
          output must contain("keepurl: http://www.avengers.org/")
          output must contain("""<img src="https://cloudfront/users/1/pics/100/0.jpg" alt="Tony Stark"/>""")
          output must contain("""<img src="https://cloudfront/users/2/pics/100/0.jpg" alt="Steve Rogers"/>""")
          output must contain("""<img src="https://cloudfront/users/3/pics/100/0.jpg" alt="Nick Fury"/>""")
          output must contain("""<img src="https://cloudfront/users/4/pics/100/0.jpg" alt="Bruce Banner"/>""")
          output must contain("""<span title="">this is a </span><a href="https://www.kifi.com/find?q=tag:%22test%22" title="">#test</a><span title=""> tag!</span>""")

          val text = processed.textBody.get.value
          text must contain("Steve Rogers and Tony Stark joined!")
          text must contain("Join my library: Avengers Missions")
          text must contain("liburl: http://dev.ezkeep.com:9000/ironman/avengers")
          text must contain("Look at this keep: Avengers$1.org")
          text must contain("keepurl: http://www.avengers.org/")
          text must contain("https://cloudfront/users/3/pics/100/0.jpg")
          text must contain("unsub1 https://www.kifi.com/unsubscribe/")
          text must contain("unsub2 https://www.kifi.com/unsubscribe/")
          text must contain("unsub3 https://www.kifi.com/unsubscribe/")
          text must contain("Unsubscribe here: https://www.kifi.com/unsubscribe/")
          text must contain("Kifi.com | 278 Hope St Suite D, Mountain View, CA 94041, USA")
        }
      }
      "do user information" in {
        withDb(modules: _*) { implicit injector =>
          val user = db.readWrite { implicit rw =>
            UserFactory.user().withName("Ryan", "Brewster").withEmailAddress("ryan@kifi.com")saved
          }

          val uid = user.id.get
          val html1 = Html(s"""${firstName(uid)} ${lastName(uid)} ${userExternalId(uid)}""")

          val processor = inject[EmailTemplateProcessorImpl]
          val emailToSend = EmailToSend(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Left(user.id.get),
            subject = "",
            htmlTemplate = html1,
            category = NotificationCategory.User.SOCIAL_FRIEND_JOINED
          )

          val outputF = processor.process(emailToSend)
          val processed = Await.result(outputF, Duration(5, "seconds"))

          val output = processed.htmlBody.value
          output must contain(s"${user.firstName} ${user.lastName} ${user.externalId}")
        }
      }
      "do org information" in {
        withDb(modules: _*) { implicit injector =>
          val (org, user) = db.readWrite { implicit rw =>
            val owner = UserFactory.user().withName("Ryan", "Brewster").withEmailAddress("ryan@kifi.com")saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            (org, owner)
          }

          val uid = user.id.get
          val html1 = Html(s"""${organizationId(org.id.get)}""")

          val processor = inject[EmailTemplateProcessorImpl]
          val emailToSend = EmailToSend(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Left(user.id.get),
            subject = "",
            htmlTemplate = html1,
            category = NotificationCategory.User.SOCIAL_FRIEND_JOINED
          )

          val outputF = processor.process(emailToSend)
          val processed = Await.result(outputF, Duration(5, "seconds"))

          val output = processed.htmlBody.value
          output must contain(s"${Organization.publicId(org.id.get).id}")
        }
      }
      "do discussion information" in {
        withDb(modules: _*) { implicit injector =>
          val (user, uri) = db.readWrite { implicit rw =>
            val user = UserFactory.user().withName("Ryan", "Brewster").withEmailAddress("ryan@kifi.com")saved
            val uri = normalizedURIInterner.internByUri("http://www.kifi.com/")
            (user, uri)
          }

          val keepId = Id[Keep](1)
          val keepPubId = Keep.publicId(keepId)(inject[PublicIdConfiguration])

          val deepLink = "http://dev.ezkeep.com:9000/redir?data=" + URLEncoder.encode(s"""{"t":"m","uri":"${uri.externalId}","id":"${keepPubId.id}"}""", "ascii")
          val html = Html(s"""${discussionLink(uri.id.get, keepPubId, None, true)}""")
          val processor = inject[EmailTemplateProcessorImpl]
          val emailToSend = EmailToSend(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Left(user.id.get),
            subject = "",
            htmlTemplate = html,
            category = NotificationCategory.User.MESSAGE
          )

          val outputF = processor.process(emailToSend)
          val processed = Await.result(outputF, Duration(5, "seconds"))

          val output = processed.htmlBody.value
          output must contain(deepLink)
        }
      }
    }
  }
}
