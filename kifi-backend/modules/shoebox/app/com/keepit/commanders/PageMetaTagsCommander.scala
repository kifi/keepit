package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.LibraryVisibility.PUBLISHED
import com.keepit.model._
import com.keepit.social.SocialNetworks
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

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
        val images: Seq[KeepImage] = keepImageCommander.getBestImagesForKeeps(keeps.map(_.id.get).toSet, ProcessedImageSize.XLarge.idealSize).values.flatten.toSeq
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
        val keeps = keepRepo.getByLibrary(library.id.get, 0, 50)
        val page = keeps.iterator.map(k => pageInfoRepo.getByUri(k.uriId)).find(p => p.exists(_.description.size > 100)).flatten
        page.flatMap(_.description)
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
          unsafeTitle = s"${library.name} by ${owner.firstName} ${owner.lastName} \u2022 Kifi",
          url = url,
          urlPathOnly = urlPathOnly,
          unsafeDescription = PublicPageMetaTags.generateMetaTagsDescription(library.description, owner.fullName, library.name, altDesc),
          images = imageUrls,
          facebookId = facebookId,
          createdAt = library.createdAt,
          updatedAt = library.updatedAt,
          unsafeFirstName = owner.firstName,
          unsafeLastName = owner.lastName,
          noIndex = lowQualityLibrary,
          related = relatedLibraiesLinks)
      }
    }
  }
}
