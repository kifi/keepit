package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3UserPictureConfig
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.LibraryVisibility.PUBLISHED
import com.keepit.model._
import com.keepit.social.SocialNetworks
import org.im4java.utils.NoiseFilter.Threshold
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import views.html.admin.library

import scala.concurrent.Future

trait UserProfileTab {
  def path: String
  def titleSuffix: String
}

object UserProfileTab {
  object UserProfileHomeTab extends UserProfileTab { val path = "/"; val titleSuffix = "" }
  object UserProfileLibrariesTab extends UserProfileTab { val path = "/libraries"; val titleSuffix = "'s Libraries" }
  object UserProfileConnectionsTab extends UserProfileTab { val path = "/connections"; val titleSuffix = "'s Connections" }
  object UserProfileFollowersTab extends UserProfileTab { val path = "/followers"; val titleSuffix = "'s Followers" }
  object UserProfileTagsTab extends UserProfileTab { val path = "/tags"; val titleSuffix = "'s Tags" }
  object UserProfileFollowLibrariesTab extends UserProfileTab { val path = "/libraries/following"; val titleSuffix = " Follows" }
  object UserProfileInvitedLibrariesTab extends UserProfileTab { val path = "/libraries/invited"; val titleSuffix = "'s Connection Invitations" }
  val tabs = Seq(UserProfileHomeTab, UserProfileLibrariesTab, UserProfileConnectionsTab, UserProfileFollowersTab, UserProfileTagsTab, UserProfileFollowLibrariesTab, UserProfileInvitedLibrariesTab).map(t => t.path -> t).toMap
  def apply(path: String): UserProfileTab = tabs(path.substring(path.indexOf("/", 1)))
}

class PageMetaTagsCommander @Inject() (
    db: Database,
    libraryImageCommander: LibraryImageCommander,
    keepImageCommander: KeepImageCommander,
    relatedLibraryCommander: RelatedLibraryCommander,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    pageInfoRepo: PageInfoRepo,
    applicationConfig: FortyTwoConfig,
    socialUserInfoRepo: SocialUserInfoRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  private def imageUrl(image: LibraryImage): String = addProtocol(libraryImageCommander.getUrl(image))

  private def imageUrl(image: KeepImage): String = addProtocol(keepImageCommander.getUrl(image))

  private def addProtocol(url: String): String = if (url.startsWith("http:") || url.startsWith("https:")) url else s"http:$url"

  private def relatedLibrariesLinks(library: Library): Future[Seq[String]] = relatedLibraryCommander.suggestedLibraries(library.id.get) map { relatedLibs =>
    val libs = relatedLibs.filterNot(_.kind == RelatedLibraryKind.POPULAR).take(6).map(_.library)
    val users = db.readOnlyMaster { implicit s => basicUserRepo.loadAll(libs.map(_.ownerId).toSet) }
    libs.map { related =>
      val urlPathOnly = Library.formatLibraryPath(users(related.ownerId).username, related.slug)
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

  def selectKeepsDescription(libraryId: Id[Library], threshold: Int = 100)(implicit s: RSession): Option[String] = {
    val keeps = keepRepo.getByLibrary(libraryId, 0, 50)
    val pages = keeps.iterator.map(k => pageInfoRepo.getByUri(k.uriId))
    val page = pages.find(p => p.exists(_.description.exists(_.size > threshold))).flatten
    val desc = page.flatMap(_.description).orElse {
      if (threshold <= 50) None
      else selectKeepsDescription(libraryId, 50)
    }
    desc
  }

  def libraryMetaTags(library: Library): Future[PublicPageMetaTags] = {
    val (owner, urlPathOnly) = db.readOnlyMaster { implicit s =>
      val owner = basicUserRepo.load(library.ownerId)
      val urlPathOnly = Library.formatLibraryPath(owner.username, library.slug)
      (owner, urlPathOnly)
    }
    val altDescF: Future[Option[String]] = if (library.description.exists(_.size > 10)) {
      Future.successful(None)
    } else {
      db.readOnlyMasterAsync { implicit s =>
        selectKeepsDescription(library.id.get)
      }
    }
    if (library.visibility != PUBLISHED) {
      Future.successful(PublicPageMetaPrivateTags(urlPathOnly))
    } else {
      val relatedLibraiesLinksF: Future[Seq[String]] = relatedLibrariesLinks(library)
      val metaInfoF = db.readOnlyMasterAsync { implicit s =>
        val facebookId: Option[String] = socialUserInfoRepo.getByUser(library.ownerId).filter(i => i.networkType == SocialNetworks.FACEBOOK).map(_.socialId.id).headOption

        val keeps = keepRepo.getByLibrary(library.id.get, 0, 50)
        val imageUrls = libraryImages(library, keeps)

        val url = {
          val fullUrl = s"${applicationConfig.applicationBaseUrl}$urlPathOnly"
          if (fullUrl.startsWith("http") || fullUrl.startsWith("https:")) fullUrl else s"http:$fullUrl"
        }

        val lowQualityLibrary: Boolean = {
          keeps.size <= 3 || ((library.description.isEmpty || library.description.get.length <= 10) && keeps.size <= 6)
        }

        (owner, url, imageUrls, facebookId, lowQualityLibrary)
      }
      for {
        (owner, url, imageUrls, facebookId, lowQualityLibrary) <- metaInfoF
        relatedLibraiesLinks <- relatedLibraiesLinksF
        altDesc <- altDescF
      } yield {
        PublicPageMetaFullTags(
          unsafeTitle = s"${library.name}",
          url = url,
          urlPathOnly = urlPathOnly,
          unsafeDescription = PublicPageMetaTags.generateLibraryMetaTagDescription(library.description, owner.fullName, library.name, altDesc),
          images = imageUrls,
          facebookId = facebookId,
          createdAt = library.createdAt,
          updatedAt = library.updatedAt,
          unsafeFirstName = owner.firstName,
          unsafeLastName = owner.lastName,
          getUserProfileUrl(owner.username),
          noIndex = lowQualityLibrary,
          related = relatedLibraiesLinks)
      }
    }
  }

  private val cdnBaseUrl = "https://djty7jcqog9qu.cloudfront.net"
  private def getProfileImageUrl(user: User): String =
    s"$cdnBaseUrl/users/${user.externalId}/pics/200/${user.pictureName.getOrElse(S3UserPictureConfig.defaultName)}.jpg"

  private def getUserProfileUrl(username: Username): String = {
    val urlPathOnly = s"/${username.value}"
    val fullUrl = s"${applicationConfig.applicationBaseUrl}$urlPathOnly"
    if (fullUrl.startsWith("http") || fullUrl.startsWith("https:")) fullUrl else s"http:$fullUrl"
  }

  def userMetaTags(user: User, tab: UserProfileTab): Future[PublicPageMetaTags] = {
    val url = getUserProfileUrl(user.username)
    val metaInfoF = db.readOnlyMasterAsync { implicit s =>
      val facebookId: Option[String] = socialUserInfoRepo.getByUser(user.id.get).filter(i => i.networkType == SocialNetworks.FACEBOOK).map(_.socialId.id).headOption
      val imageUrl = getProfileImageUrl(user)
      (imageUrl, facebookId)
    }
    val countLibrariesF = db.readOnlyMasterAsync { implicit s =>
      libraryMembershipRepo.countWithUserIdAndAccess(user.id.get, LibraryAccess.OWNER) + libraryMembershipRepo.countWithUserIdAndAccess(user.id.get, LibraryAccess.READ_ONLY)
    }
    for {
      (imageUrl, facebookId) <- metaInfoF
      countLibraries <- countLibrariesF
    } yield {
      PublicPageMetaFullTags(
        unsafeTitle = s"${user.firstName} ${user.lastName}${tab.titleSuffix}",
        url = url + tab.path,
        urlPathOnly = url + tab.path,
        unsafeDescription = s"${user.firstName} ${user.lastName}${tab.titleSuffix} on Kifi. Join Kifi to connect with ${user.firstName} ${user.lastName} and others you may know. Kifi connects people with knowledge.",
        images = Seq(imageUrl),
        facebookId = facebookId,
        createdAt = user.createdAt,
        updatedAt = user.updatedAt,
        unsafeFirstName = user.firstName,
        unsafeLastName = user.lastName,
        profileUrl = url,
        noIndex = countLibraries <= 2, //means at least one library they follow or own, ignoring the main and private libs
        related = Seq.empty)
    }
  }
}
