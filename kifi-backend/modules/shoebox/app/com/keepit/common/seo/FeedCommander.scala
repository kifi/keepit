package com.keepit.common.seo

import java.util.Locale

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.CollectionHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.common.time._
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import org.joda.time.DateTime
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
    s3ImageConfig: S3ImageConfig,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    keepImageCommander: KeepImageCommander,
    libraryImageCommander: LibraryImageCommander,
    libPathCommander: PathCommander,
    libraryCommander: PageMetaTagsCommander,
    rover: RoverServiceClient) extends Logging {

  val dateTimeFormatter = DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.US)

  def wrap(elem: Elem): Enumerator[Array[Byte]] = {
    val elems = Enumerator.enumerate(elem)
    val toBytes = Enumeratee.map[Node] { n => n.toString.getBytes }
    val header = Enumerator("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".getBytes)
    header.andThen(elems &> toBytes)
  }

  case class RssItem(title: String, description: String, link: String, guid: String, pubDate: DateTime, creator: String, icon: Option[String])

  private def rssItems(items: Seq[RssItem]): Seq[Elem] = {
    items map { item =>
      <item>
        <title>{ item.title }</title>
        <description>{ item.description }</description>
        <link>{ item.link }</link>
        <guid isPermaLink="false">{ item.guid }</guid>
        <pubDate>{ dateTimeFormatter.print(item.pubDate) }</pubDate>
        <dc:creator>{ item.creator }</dc:creator>
        {
          if (item.icon.nonEmpty) {
            <media:thumbnail url={ item.icon.get }/>
            <media:content url={ item.icon.get } medium="image">
              <media:title type="html">
                { item.title }
              </media:title>
            </media:content>
          }
        }
      </item>
    }
  }

  def libraryFeed(library: Library, keepCountToDisplay: Int = 20, offset: Int = 0): Future[Elem] = {
    val (libImage, keeps, libraryCreator) = db.readOnlyMaster { implicit session =>
      val image = libraryImageCommander.getBestImageForLibrary(library.id.get, ImageSize(100, 100))
      val keeps = keepRepo.pageByLibrary(libraryId = library.id.get, offset = offset, limit = keepCountToDisplay, excludeSet = Set(KeepStates.INACTIVE))
      val libraryCreator = userRepo.get(library.ownerId)
      (image.map(_.imagePath.getUrl(s3ImageConfig)), keeps, libraryCreator)
    }
    val feedUrl = s"${fortyTwoConfig.applicationBaseUrl}${libPathCommander.getPathForLibraryUrlEncoded(library)}"

    val descriptionsFuture = db.readOnlyMaster { implicit s => rover.getUriSummaryByUris(keeps.map(_.uriId).toSet) }
    descriptionsFuture map { descriptions =>
      <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/" xmlns:atom="http://www.w3.org/2005/Atom">
        <channel>
          <title>{ library.name } by { libraryCreator.fullName } * Kifi</title>
          <link>{ feedUrl }</link>
          <description>{ library.description.getOrElse("") }</description>
          {
            if (libImage.nonEmpty) {
              <image>
                <url>{ s"https:${libImage.get}" }</url>
                <title>{ library.name } by { libraryCreator.fullName } * Kifi</title>
                <link>{ feedUrl }</link>
              </image>
            }
          }
          <copyright>Copyright { currentDateTime.getYear }, FortyTwo Inc.</copyright>
          <atom:link rel="self" type="application/rss+xml" href={ feedUrl }/>
          <atom:link rel="hub" href="https://pubsubhubbub.appspot.com/"/>
          {
            def convertKeep(keep: Keep): RssItem = {
              val (keepImage, originalKeeper) = db.readOnlyMaster { implicit s =>
                val image = keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(ImageSize(100, 100)))
                (image.flatten, keep.userId.map(userRepo.getNoCache))
              }

              RssItem(title = keep.title.getOrElse(""), description = descriptions.get(keep.uriId).flatMap(_.article.description).getOrElse(""), link = keep.url,
                guid = keep.externalId.id, pubDate = keep.keptAt, creator = originalKeeper.map(_.fullName).getOrElse("unknown"),
                icon = keepImage.map(_.imagePath.getUrl(s3ImageConfig)).map(url => s"https:$url"))
            }
            rssItems(keeps map convertKeep)
          }{ /* License asking for attribution */ }
        </channel>
      </rss>
    }
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
        val itemUrl = s"${fortyTwoConfig.applicationBaseUrl}${libPathCommander.getPathForLibrary(lib)}"
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
