package com.keepit.controllers.core

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URI
import com.keepit.common.social.{UserWithSocial, UserWithSocialRepo}
import com.keepit.search.graph.URIGraph

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo, DomainStates}

import com.keepit.model._

import com.google.inject.{Inject, Singleton}

case class SliderInfo(
  bookmark: Option[Bookmark],
  following: Boolean,
  socialUsers: Seq[UserWithSocial],
  numComments: Int,
  numMessages: Int,
  neverOnSite: Option[UserToDomain],
  sensitive: Option[Boolean])
case class SliderInitialInfo(
  bookmark: Option[Bookmark],
  socialUsers: Seq[UserWithSocial],
  numKeeps: Int,
  numComments: Int,
  numUnreadComments: Int,
  numMessages: Int,
  numUnreadMessages: Int,
  neverOnSite: Option[UserToDomain],
  sensitive: Option[Boolean],
  locator: Option[DeepLocator],
  shown: Option[Boolean],
  ruleGroup: Option[SliderRuleGroup],
  patterns: Option[Seq[String]])

@Singleton
class SliderInfoLoader @Inject() (db: Database,
  normalizedURIRepo: NormalizedURIRepo, domainRepo: DomainRepo, userToDomainRepo: UserToDomainRepo, socialConnectionRepo: SocialConnectionRepo, userRepo: UserRepo,
  followRepo: FollowRepo, bookmarkRepo: BookmarkRepo, commentRepo: CommentRepo, commentReadRepo: CommentReadRepo,
  domainClassifier: DomainClassifier, uriGraph: URIGraph, userWithSocialRepo: UserWithSocialRepo, historyTracker: SliderHistoryTracker,
  sliderRuleRepo: SliderRuleRepo, urlPatternRepo: URLPatternRepo) {

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

        val friendIds = socialConnectionRepo.getFortyTwoUserConnections(userId)
        val searcher = uriGraph.getURIGraphSearcher
        val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
        val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId
        val socialUsers = sharingUserIds.map(u => userWithSocialRepo.toUserWithSocial(userRepo.get(u))).toSeq

        val numComments = commentRepo.getPublicCount(uri.id.get)
        val numMessages = commentRepo.getMessages(uri.id.get, userId).size

        SliderInfo(bookmark, following, socialUsers, numComments, numMessages, neverOnSite, sensitive)
      case None =>
        val sensitive = domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption))
        SliderInfo(None, false, Nil, 0, 0, neverOnSite, sensitive)
    }
  }

  def initialLoad(userId: Id[User], url: String, ver: String) = db.readOnly { implicit s =>
    val nUri = normalizedURIRepo.getByNormalizedUrl(url)
    val host: Option[String] = URI.parse(url).get.host.map(_.name)
    val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
    val neverOnSite: Option[UserToDomain] = domain.flatMap { domain =>
      userToDomainRepo.get(userId, domain.id.get, UserToDomainKinds.NEVER_SHOW)
    }
    val ruleGroup: Option[SliderRuleGroup] = Option(sliderRuleRepo.getGroup("default")).filter(_.version != ver)
    val patterns: Option[Seq[String]] = ruleGroup.map(_ => urlPatternRepo.getActivePatterns)

    nUri match {
      case Some(uri) =>
        val bookmark = bookmarkRepo.getByUriAndUser(uri.id.get, userId)
        val sensitive: Option[Boolean] = bookmark.flatMap(b => domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption)))

        val friendIds = socialConnectionRepo.getFortyTwoUserConnections(userId)
        val searcher = uriGraph.getURIGraphSearcher
        val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
        val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId
        val socialUsers = sharingUserIds.map(u => userWithSocialRepo.toUserWithSocial(userRepo.get(u))).toSeq

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

        SliderInitialInfo(
          bookmark, socialUsers, keepersEdgeSet.size, numComments, numUnreadComments, numMessages, numUnreadMessages,
          neverOnSite, sensitive, locator, shown, ruleGroup, patterns)
      case None =>
        val sensitive: Option[Boolean] = domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption))
        SliderInitialInfo(None, Nil, 0, 0, 0, 0, 0, neverOnSite, sensitive, None, None, None, None)
    }
  }
}
