package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ S3ImageConfig, S3UserPictureConfig }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.LibraryVisibility.PUBLISHED
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.social.{ BasicAuthor, SocialNetworks }
import com.keepit.common.core._
import org.apache.commons.lang3.RandomStringUtils

import scala.concurrent.{ ExecutionContext, Future }

trait UserProfileTab {
  def paths: Seq[String]
  def title(name: String): String
}

object UserProfileTab {
  object Libraries extends UserProfileTab { val paths = Seq("", "/libraries"); def title(name: String) = s"$name’s Libraries" }
  object FollowingLibraries extends UserProfileTab { val paths = Seq("/libraries/following"); def title(name: String) = s"Libraries $name Follows" }
  object InvitedLibraries extends UserProfileTab { val paths = Seq("/libraries/invited"); def title(name: String) = s"$name’s Library Invitations" }
  object Connections extends UserProfileTab { val paths = Seq("/connections"); def title(name: String) = s"$name’s Connections" }
  object Followers extends UserProfileTab { val paths = Seq("/followers"); def title(name: String) = s"$name’s Followers" }
  val all = Seq(Libraries, FollowingLibraries, InvitedLibraries, Connections, Followers)
  private val byPath = all.flatMap(t => t.paths.flatMap(p => Seq(p -> t, p + '/' -> t))).toMap
  def apply(path: String): UserProfileTab = {
    val i = path.indexOf("/", 1)
    byPath(if (i < 0) "" else path.substring(i))
  }
}

class PageMetaTagsCommander @Inject() (
    db: Database,
    libraryImageCommander: LibraryImageCommander,
    pathCommander: PathCommander,
    keepImageCommander: KeepImageCommander,
    keepSourceCommander: KeepSourceCommander,
    relatedLibraryCommander: RelatedLibraryCommander,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    applicationConfig: FortyTwoConfig,
    socialUserInfoRepo: SocialUserInfoRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    rover: RoverServiceClient,
    implicit val imageConfig: S3ImageConfig,
    implicit val executionContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  private def imageUrl(image: LibraryImage): String = addProtocol(libraryImageCommander.getUrl(image))

  private def imageUrl(image: KeepImage): String = addProtocol(keepImageCommander.getUrl(image))

  private def addProtocol(url: String): String = if (url.startsWith("http:") || url.startsWith("https:")) url else s"http:$url"

  private def relatedLibrariesLinks(library: Library): Future[Seq[String]] = relatedLibraryCommander.suggestedLibraries(library.id.get, None) map { relatedLibs =>
    val libs = relatedLibs.filterNot(_.kind == RelatedLibraryKind.POPULAR).take(6).map(_.library)
    val users = db.readOnlyMaster { implicit s => basicUserRepo.loadAll(libs.map(_.ownerId).toSet) }
    libs.map { related =>
      val urlPathOnly = pathCommander.getPathForLibrary(related)
      val url = {
        val fullUrl = s"${applicationConfig.applicationBaseUrl}$urlPathOnly"
        if (fullUrl.startsWith("http") || fullUrl.startsWith("https:")) fullUrl else s"http:$fullUrl"
      }
      url
    }
  }

  private def libraryImages(library: Library, keeps: Seq[Keep]): Seq[String] = {
    libraryImageCommander.getBestImageForLibrary(library.id.get, ProcessedImageSize.XLarge.idealSize) match {
      case Some(image) =>
        Seq(imageUrl(image))
      case None =>
        val images: Seq[KeepImage] = keepImageCommander.getBestImagesForKeeps(keeps.map(_.id.get).toSet, ScaleImageRequest(ProcessedImageSize.XLarge.idealSize)).values.flatten.toSeq
        val sorted: Seq[KeepImage] = images.sortWith {
          case (image1, image2) =>
            (image1.imageSize.width * image1.imageSize.height) > (image2.imageSize.width * image2.imageSize.height)
        }
        val urls: Seq[String] = sorted.take(10) map { image =>
          imageUrl(image)
        }
        //last image is the kifi image we want to append to all image lists
        if (urls.isEmpty) Seq("https://djty7jcqog9qu.cloudfront.net/assets/fbc1200X630.png") else urls
    }
  }

  def selectKeepsDescription(libraryId: Id[Library]): Future[Option[String]] = {
    val futureKeeps = db.readOnlyMasterAsync { implicit session =>
      keepRepo.getByLibrary(libraryId, 0, 50)
    }
    futureKeeps.flatMap { keeps =>

      val descriptionFutures: Stream[Future[Option[String]]] = keeps.toStream.map { keep =>
        rover.getArticleSummaryByUris(Set(keep.uriId)).imap(_.get(keep.uriId).flatMap(_.description))
      }

      def collectFirstLongerThanThreshold(threshold: Int): Future[Option[String]] = {
        FutureHelpers.collectFirst(descriptionFutures) { descriptionFuture =>
          descriptionFuture.imap(_.filter(_.length > threshold))
        }
      }

      collectFirstLongerThanThreshold(100).flatMap {
        case Some(longEnoughDescription) => Future.successful(Some(longEnoughDescription))
        case None => collectFirstLongerThanThreshold(50)
      }
    }
  }

  def libraryMetaTags(library: Library): Future[PublicPageMetaTags] = {
    val (owner, urlPathOnly) = db.readOnlyMaster { implicit s =>
      val owner = basicUserRepo.load(library.ownerId)
      val urlPathOnly = pathCommander.getPathForLibrary(library)
      (owner, urlPathOnly)
    }
    val altDescF: Future[Option[String]] = if (library.description.exists(_.size > 10)) {
      Future.successful(None)
    } else {
      selectKeepsDescription(library.id.get)
    }
    if (library.visibility != PUBLISHED) {
      Future.successful(PublicPageMetaPrivateTags(urlPathOnly))
    } else {
      val relatedLibrariesLinksF: Future[Seq[String]] = relatedLibrariesLinks(library)
      val metaInfoF = db.readOnlyMasterAsync { implicit s =>
        val facebookId: Option[String] = socialUserInfoRepo.getByUser(library.ownerId).filter(i => i.networkType == SocialNetworks.FACEBOOK).map(_.socialId.id).headOption

        val keeps = keepRepo.getByLibrary(library.id.get, 0, 50)
        val imageUrls = libraryImages(library, keeps)

        val url = {
          val fullUrl = s"${applicationConfig.applicationBaseUrl}$urlPathOnly"
          if (fullUrl.startsWith("http") || fullUrl.startsWith("https:")) fullUrl else s"http:$fullUrl"
        }

        val lowQualityLibrary: Boolean = {
          keeps.size <= 2 || ((library.description.isEmpty || library.description.get.length <= 10) && keeps.size <= 4)
        }

        (owner, url, imageUrls, facebookId, lowQualityLibrary)
      }
      for {
        (owner, url, imageUrls, facebookId, lowQualityLibrary) <- metaInfoF
        relatedLibrariesLinks <- relatedLibrariesLinksF
        altDesc <- altDescF
      } yield {
        PublicPageMetaFullTags(
          unsafeTitle = s"${library.name}",
          url = url,
          urlPathOnly = urlPathOnly,
          feedName = Some(library.name),
          unsafeDescription = PublicPageMetaTags.generateLibraryMetaTagDescription(library.description, owner.fullName, library.name, altDesc),
          images = imageUrls,
          facebookId = facebookId,
          createdAt = library.createdAt,
          updatedAt = library.updatedAt,
          unsafeFirstName = owner.firstName,
          unsafeLastName = owner.lastName,
          getUserProfileUrl(owner.username),
          noIndex = lowQualityLibrary,
          related = relatedLibrariesLinks)
      }
    }
  }

  private val cdnBaseUrl = "https://djty7jcqog9qu.cloudfront.net"
  private def getProfileImageUrl(user: User): String =
    s"$cdnBaseUrl/users/${user.externalId}/pics/200/${user.pictureName.getOrElse(S3UserPictureConfig.defaultName)}.jpg"

  private def getProfileUrl[T](info: T, subPath: T => String): String = {
    val fullUrl = s"${applicationConfig.applicationBaseUrl}${subPath(info)}"
    if (fullUrl.startsWith("http") || fullUrl.startsWith("https:")) fullUrl else s"http:$fullUrl"
  }

  private def getUserProfileUrl(username: Username): String = getProfileUrl(username, userPathOnly)
  private def getOrgProfileUrl(primaryHandle: PrimaryOrganizationHandle): String = getProfileUrl(primaryHandle, orgPathOnly)
  private def getKeepProfileUrl(keep: Keep): String = getProfileUrl(keep, keepPathOnly)

  private def userPathOnly(username: Username): String = s"/${username.value}"
  private def orgPathOnly(primaryHandle: PrimaryOrganizationHandle): String = s"/${primaryHandle.original.value}"
  private def keepPathOnly(keep: Keep): String = keep.path.relative

  def userMetaTags(user: User, tab: UserProfileTab): Future[PublicPageMetaTags] = {
    val urlPath = userPathOnly(user.username)
    val url = getUserProfileUrl(user.username)
    val metaInfoF = db.readOnlyMasterAsync { implicit s =>
      val facebookId: Option[String] = socialUserInfoRepo.getByUser(user.id.get).filter(i => i.networkType == SocialNetworks.FACEBOOK).map(_.socialId.id).headOption
      val imageUrl = getProfileImageUrl(user)
      (imageUrl, facebookId)
    }
    val countLibrariesF = db.readOnlyMasterAsync { implicit s =>
      libraryMembershipRepo.countNonTrivialLibrariesWithUserIdAndAccess(user.id.get, LibraryAccess.OWNER) + libraryMembershipRepo.countNonTrivialLibrariesWithUserIdAndAccess(user.id.get, LibraryAccess.READ_ONLY)
    }
    for {
      (imageUrl, facebookId) <- metaInfoF
      countLibraries <- countLibrariesF
    } yield {
      val title = tab.title(s"${user.firstName} ${user.lastName}")
      PublicPageMetaFullTags(
        unsafeTitle = title,
        url = url + tab.paths.head,
        urlPathOnly = urlPath + tab.paths.head,
        feedName = None,
        unsafeDescription = s"$title on Kifi. Join Kifi to connect with ${user.firstName} and others you may know. Kifi connects people with knowledge.",
        images = Seq(imageUrl),
        facebookId = facebookId,
        createdAt = user.createdAt,
        updatedAt = user.updatedAt,
        unsafeFirstName = user.firstName,
        unsafeLastName = user.lastName,
        profileUrl = url,
        noIndex = countLibraries == 0, //no public libraries - no index
        related = Seq.empty)
    }
  }

  def orgMetaTags(org: Organization): Future[PublicPageMetaTags] = {
    val urlPath = org.primaryHandle.map(h => orgPathOnly(h))
    val url = org.primaryHandle.map(h => getOrgProfileUrl(h))
    val countLibrariesF = db.readOnlyMasterAsync { implicit s =>
      libraryRepo.countPublishedNonEmptyOrgLibraries(org.id.get)
    }
    for {
      countLibraries <- countLibrariesF
    } yield {
      val title = s"${org.name} Team on Kifi"
      PublicPageMetaFullTags(
        unsafeTitle = title,
        url = url.getOrElse(""),
        urlPathOnly = urlPath.getOrElse(""),
        feedName = None,
        unsafeDescription = s"${org.description.getOrElse(title)}",
        images = Seq(),
        facebookId = None,
        createdAt = org.createdAt,
        updatedAt = org.updatedAt,
        unsafeFirstName = "",
        unsafeLastName = "",
        profileUrl = url.getOrElse(""),
        noIndex = countLibraries == 0, //no public libraries - no index
        related = Seq.empty)
    }
  }

  def keepMetaTags(keep: Keep): Future[PublicPageMetaTags] = {
    val urlPath = keepPathOnly(keep)
    val url = getKeepProfileUrl(keep)
    val authorFut = db.readOnlyMasterAsync { implicit s =>
      val source = keepSourceCommander.getSourceAttributionForKeeps(Set(keep.id.get)).get(keep.id.get)
      source.map { case (attr, userOpt) => BasicAuthor(attr, userOpt) }
    }
    val librariesFut = db.readOnlyMasterAsync(implicit s => libraryRepo.getActiveByIds(keep.connections.libraries))
    val imageFut = db.readOnlyMasterAsync { implicit s =>
      keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(ProcessedImageSize.XLarge.idealSize)).flatten
    }
    for {
      authorOpt <- authorFut
      libraries <- librariesFut
      imageOpt <- imageFut
    } yield {
      val splitName = authorOpt.map(_.name.split(" "))
      PublicPageMetaFullTags(
        unsafeTitle = keep.title.getOrElse(""),
        url = url,
        urlPathOnly = urlPath,
        feedName = None,
        unsafeDescription = keep.note.orElse(keep.title).getOrElse(""),
        images = imageOpt.map(imageUrl).toSeq,
        facebookId = None,
        createdAt = keep.createdAt,
        updatedAt = keep.updatedAt,
        unsafeFirstName = splitName.flatMap(_.headOption).getOrElse(""),
        unsafeLastName = splitName.flatMap(_.lastOption).getOrElse(""),
        profileUrl = url,
        noIndex = libraries.values.count(_.visibility == PUBLISHED) == 0,
        related = Seq.empty
      )
    }
  }
}
