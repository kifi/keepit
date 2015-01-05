package com.keepit.common.seo

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ PageMetaTagsCommander, LibraryCommander, PublicPageMetaFullTags }
import com.keepit.common.CollectionHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ Library, LibraryMembershipRepo, LibraryRepo, UserRepo }
import org.joda.time.format.DateTimeFormat
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.{ Enumeratee, Enumerator }

import scala.concurrent.Future
import scala.xml.{ Elem, Node, Unparsed }

@Singleton
class FeedCommander @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock,
    fortyTwoConfig: FortyTwoConfig,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryCommander: PageMetaTagsCommander) extends Logging {

  def wrap(elem: Elem): Enumerator[Array[Byte]] = {
    val elems = Enumerator.enumerate(elem)
    val toBytes = Enumeratee.map[Node] { n => n.toString.getBytes }
    val header = Enumerator("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".getBytes)
    header.andThen(elems &> toBytes)
  }

  def rss(feedTitle: String, feedUrl: String, libs: Seq[Library]): Future[Elem] = {
    val ownerIds = CollectionHelpers.dedupBy(libs.map(_.ownerId))(id => id)
    val owners = db.readOnlyMaster { implicit ro => userRepo.getUsers(ownerIds) } // cached

    val metaTagsF = Future.sequence(libs.map { lib =>
      libraryCommander.libraryMetaTags(lib) map { tags =>
        lib.id.get -> (tags match {
          case pub: PublicPageMetaFullTags => pub
          case _ => throw new IllegalStateException(s"Failed to retrieve public meta tags for $lib; tags=$tags") // shouldn't happen (can add recovery)
        })
      }
    })

    val logo = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png"

    metaTagsF map { metaTags =>
      val idToMetaTags = metaTags.toMap
      val formatter = DateTimeFormat.forPattern("E, d MMM y HH:mm:ss Z")
      val items = libs map { lib =>
        val metaTags = idToMetaTags(lib.id.get)
        val owner = owners(lib.ownerId)
        val libImg = metaTags.images.headOption.getOrElse(logo)
        val itemUrl = s"${fortyTwoConfig.applicationBaseUrl}${Library.formatLibraryPath(owner.username, lib.slug)}"
        val desc = Unparsed(
          s"""
               |<![CDATA[
               |<img src="$libImg"/>
               |${metaTags.description}
               |]]>
               |""".stripMargin)
        <item>
          <title>
            { metaTags.title }
          </title>
          <description>
            { desc }
          </description>
          <link>
            { itemUrl }
          </link>
          <guid>
            { itemUrl }
          </guid>
          <pubDate>
            { metaTags.updatedAt.toString(formatter) }
          </pubDate>
          <dc:creator>
            { metaTags.fullName }
          </dc:creator>
          <media:thumbnail url={ libImg } medium="image"/>
          <media:content url={ libImg } medium="image">
            <media:title type="html">
              { metaTags.title }
            </media:title>
          </media:content>
        </item>
      }

      val year = currentDateTime.getYear
      val rss =
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/" xmlns:atom="http://www.w3.org/2005/Atom">
          <channel>
            <title>
              { feedTitle }
            </title>
            <link>
              { feedUrl }
            </link>
            <description>
              { feedTitle }
            </description>
            <image>
              <url>
                { feedUrl }
              </url>
              <title>
                { feedTitle }
              </title>
              <link>
                { fortyTwoConfig.applicationBaseUrl }
              </link>
            </image>
            <copyright>Copyright { year }, FortyTwo Inc.</copyright>
            <atom:link ref="self" type="application/rss+xml" href={ feedUrl }/>
            <atom:link ref="hub" href="https://pubsubhubbub.appspot.com/"/>{ items }
          </channel>
        </rss>

      log.info(s"[rss($feedTitle)] #items=${items.size}")
      rss
    }
  }
}
