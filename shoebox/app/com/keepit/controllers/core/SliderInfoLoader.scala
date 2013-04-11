package com.keepit.controllers.core

import com.google.inject.{Inject, Singleton}
import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.URI
import com.keepit.common.social.{UserWithSocial, UserWithSocialRepo}
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.social.BasicUser

case class SliderInfo(
  bookmark: Option[Bookmark],
  following: Boolean,
  socialUsers: Seq[BasicUser],
  numComments: Int,
  numMessages: Int,
  neverOnSite: Option[UserToDomain],
  sensitive: Option[Boolean])
case class SliderInitialInfo(
  bookmark: Option[Bookmark],
  socialUsers: Seq[BasicUser],
  numKeeps: Int,
  numComments: Int,
  numUnreadComments: Int,
  numMessages: Int,
  numUnreadMessages: Int,
  neverOnSite: Option[UserToDomain],
  sensitive: Option[Boolean],
  locator: Option[DeepLocator],
  shown: Option[Boolean])

@Singleton
class SliderInfoLoader @Inject() (
    db: Database,
    normalizedURIRepo: NormalizedURIRepo,
    domainRepo: DomainRepo,
    userToDomainRepo: UserToDomainRepo,
    socialConnectionRepo: SocialConnectionRepo,
    userRepo: UserRepo,
    followRepo: FollowRepo,
    bookmarkRepo: BookmarkRepo,
    commentRepo: CommentRepo,
    commentReadRepo: CommentReadRepo,
    domainClassifier: DomainClassifier,
    basicUserRepo: BasicUserRepo,
    historyTracker: SliderHistoryTracker,
    searchClient: SearchServiceClient) {

  def load(userId: Id[User], url: String): SliderInfo = db.readOnly {implicit s =>
    val nUri = normalizedURIRepo.getByNormalizedUrl(url)
    val host: Option[String] = URI.parse(url).get.host.map(_.name)
    val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
    val neverOnSite: Option[UserToDomain] = domain.flatMap { domain =>
      userToDomainRepo.get(userId, domain.id.get, UserToDomainKinds.NEVER_SHOW)
    }
    nUri match {
      case Some(uri) =>
        val bookmark = bookmarkRepo.getByUriAndUser(uri.id.get, userId)
        val sensitive: Option[Boolean] = bookmark.flatMap(b => domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption)))

        val following = followRepo.get(userId, uri.id.get).isDefined

        val sharingUserInfo = Await.result(searchClient.sharingUserInfo(userId, uri.id.get), Duration.Inf)
        val sharingUserIds = sharingUserInfo.sharingUserIds
        val socialUsers = sharingUserIds.map(basicUserRepo.load).toSeq

        val numComments = commentRepo.getPublicCount(uri.id.get)
        val numMessages = commentRepo.getMessages(uri.id.get, userId).size

        SliderInfo(bookmark, following, socialUsers, numComments, numMessages, neverOnSite, sensitive)
      case None =>
        val sensitive = domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption))
        SliderInfo(None, false, Nil, 0, 0, neverOnSite, sensitive)
    }
  }

  def initialLoad(userId: Id[User], url: String): SliderInitialInfo = {
    val (nUri, bookmark, numComments, numUnreadComments, numMessages, numUnreadMessages,
         neverOnSite, sensitive, locator, shown) = db.readOnly { implicit session =>
      val nUri = normalizedURIRepo.getByNormalizedUrl(url)
      val host: Option[String] = URI.parse(url).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val neverOnSite: Option[UserToDomain] = domain.flatMap { domain =>
        userToDomainRepo.get(userId, domain.id.get, UserToDomainKinds.NEVER_SHOW)
      }
      val sensitive: Option[Boolean] = domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption))

      nUri match {
        case Some(uri) =>
          val bookmark = bookmarkRepo.getByUriAndUser(uri.id.get, userId)
          val numComments = commentRepo.getPublicCount(uri.id.get)
          val numUnreadComments = commentReadRepo.getUnreadCommentsCount(userId, uri.id.get)
          val numMessages = commentRepo.getMessages(uri.id.get, userId).size
          val unreadMessages = commentReadRepo.getParentsOfUnreadMessages(userId, uri.id.get)
          val numUnreadMessages = unreadMessages.size
          val locator: Option[DeepLocator] = numUnreadMessages match {
            case 0 => if (numUnreadComments > 0) Some(DeepLocator.ofCommentList) else None
            case 1 => Some(DeepLocator.ofMessageThread(unreadMessages.head))
            case _ => Some(DeepLocator.ofMessageThreadList)
          }
          val shown: Option[Boolean] = if (locator.isDefined) None else uri.id.map { uriId =>
            historyTracker.getMultiHashFilter(userId).mayContain(uriId.id)
          }
          (nUri, bookmark, numComments, numUnreadComments, numMessages, numUnreadMessages,
           neverOnSite, sensitive, locator, shown)
        case None =>
          (nUri, None, 0, 0, 0, 0, neverOnSite, sensitive, None, None)
      }
    }
    val (socialUsers, numKeeps) = nUri map { uri =>
      val sharingUserInfo = Await.result(searchClient.sharingUserInfo(userId, uri.id.get), Duration.Inf)
      val socialUsers = db.readOnly { implicit session =>
        sharingUserInfo.sharingUserIds.map(basicUserRepo.load).toSeq
      }
      (socialUsers, sharingUserInfo.keepersEdgeSetSize)
    } getOrElse (Nil, 0)
    SliderInitialInfo(
      bookmark, socialUsers, numKeeps, numComments, numUnreadComments, numMessages, numUnreadMessages,
      neverOnSite, sensitive, locator, shown)
  }
}
