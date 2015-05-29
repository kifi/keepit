package com.keepit.common.seo

import com.google.inject.Inject
import com.keepit.commanders.{ KeepImageCommander, ScaleImageRequest, LibraryImageCommander }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.common.time._
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }
import scala.xml.{ Elem, Text }

class AtomCommander @Inject() (
    db: Database,
    fortyTwoConfig: FortyTwoConfig,
    s3ImageConfig: S3ImageConfig,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    keepImageCommander: KeepImageCommander,
    libraryImageCommander: LibraryImageCommander,
    rover: RoverServiceClient) extends Logging {

  final case class AtomLink(href: String, rel: Option[String] = None, linkType: Option[String] = None) {
    def xml: Elem = <link href={ href } rel={ rel.map(Text(_)) } type={ linkType.map(Text(_)) }></link>
  }

  final case class AtomEntry(title: String, author: String, content: Option[String],
      id: String, updatedAt: DateTime, link: AtomLink, icon: Option[String] = None,
      logo: Option[String] = None, rights: Option[String] = None) {
    def xml: Elem = {
      <entry>
        <title>{ title }</title>
        <author>
          <name>{ author }</name>
        </author>
        {
          if (content.isDefined) <content>{ content.get }</content>
        }<id>urn:kifi:{ id }</id>
        <updated>{ updatedAt }</updated>
        { if (icon.isDefined) <icon>{ icon.get }</icon> }
        { link.xml }
        { if (rights.isDefined) <rights>{ rights.get }</rights> }
      </entry>
    }
  }

  final case class AtomFeed(title: String, author: String, id: String, updatedAt: DateTime,
      links: Seq[AtomLink], entries: Seq[AtomEntry], subtitle: Option[String] = None,
      icon: Option[String] = None, logo: Option[String] = None, rights: Option[String] = None) {
    def xml: Elem = {
      <feed xmlns="http://www.w3.org/2005/Atom">
        <title>{ title }</title>
        <author>
          <name>{ author }</name>
        </author>
        { if (subtitle.isDefined) <subtitle>{ subtitle.get }</subtitle> }
        <id>urn:kifi:{ id }</id>
        <updated>{ updatedAt }</updated>
        { if (icon.isDefined) <icon>{ icon.get }</icon> }
        { if (logo.isDefined) <logo>{ logo.get }</logo> }
        { links.map(_.xml) }
        { entries.map(_.xml) }
        { if (rights.isDefined) <rights>{ rights.get }</rights> }
      </feed>
    }
  }

  def libraryFeed(library: Library, keepCountToDisplay: Int = 20, offset: Int = 0)(implicit ec: ExecutionContext): Future[Elem] = {
    val (libImage, keeps, libraryCreator) = db.readOnlyMaster { implicit session =>
      val image = libraryImageCommander.getBestImageForLibrary(library.id.get, ImageSize(64, 64))
      val keeps = keepRepo.getByLibrary(libraryId = library.id.get, offset = offset, limit = keepCountToDisplay, excludeSet = Set(KeepStates.INACTIVE))
      val libraryCreator = userRepo.get(library.ownerId)
      (image.map(i => s"https:${i.imagePath.getUrl(s3ImageConfig)}"), keeps, libraryCreator)
    }
    val feedUrl = s"${fortyTwoConfig.applicationBaseUrl}${Library.formatLibraryPathUrlEncoded(libraryCreator.username, library.slug)}"

    val descriptionsFuture = db.readOnlyMaster { implicit s => rover.getUriSummaryByUris(keeps.map(_.uriId).toSet) }
    descriptionsFuture map { descriptions =>
      val entries = keeps.map { keep =>
        val (keepImageOpt, keeper) = db.readOnlyMaster { implicit s =>
          val image = keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(ImageSize(100, 100)))
          (image, userRepo.getNoCache(keep.userId))
        }
        val title = keep.title.getOrElse("")
        val author = keeper.fullName
        val content = descriptions.get(keep.uriId).flatMap(_.article.description).getOrElse("")
        val id = keep.externalId.id
        val updatedAt = keep.updatedAt
        val link = AtomLink(keep.url)
        val keepImage = keepImageOpt.map(_.get).map(_.imagePath.getUrl(s3ImageConfig)).map(url => s"https:$url")
        AtomEntry(title, author, Some(content), id, updatedAt, link, icon = keepImage)
      }
      val links = Seq(AtomLink(feedUrl + "/atom", rel = Some("self")), AtomLink(feedUrl))
      AtomFeed(s"${library.name} by ${libraryCreator.username.value} * Kifi", libraryCreator.username.value,
        library.universalLink, library.updatedAt, links, entries, icon = libImage, logo = None, rights = Some(s"Copyright ${currentDateTime.getYear}, FortyTwo Inc.")).xml
    }
  }
}
