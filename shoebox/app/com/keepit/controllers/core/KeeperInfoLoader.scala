package com.keepit.controllers.core

import com.google.inject.{Inject, Singleton}
import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.URI
import com.keepit.common.social.{CommentWithBasicUser, ThreadInfo, UserWithSocial, UserWithSocialRepo}
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.serializer.CommentWithBasicUserSerializer.commentWithBasicUserSerializer
import com.keepit.serializer.ThreadInfoSerializer.threadInfoSerializer
import com.keepit.serializer.UserWithSocialSerializer.userWithSocialSerializer
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import org.joda.time.DateTime
import com.keepit.common.time._

case class KeeperInfo1(  // information needed immediately when a page is visited
    kept: Option[String],
    sensitive: Boolean)

object KeeperInfo1 {
  implicit val writesKeeperInfo1 = (
    (__ \ 'kept).writeNullable[String] and
    (__ \ 'sensitive).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity))
  )(unlift(KeeperInfo1.unapply))
}

case class KeeperInfo2(  // supplemental information
    shown: Boolean,
    neverOnSite: Boolean,
    keepers: Seq[UserWithSocial],
    keeps: Int,
    following: Boolean,
    comments: Seq[CommentWithBasicUser],
    threads: Seq[ThreadInfo],
    lastCommentRead: Option[DateTime],
    lastMessageRead: Map[ExternalId[Comment], DateTime])  // keys are parent IDs (thread IDs)

object KeeperInfo2 {

  implicit val writesKeeperInfo2 = new Writes[KeeperInfo2] {  // TODO: rewrite fancy :D
    def writes(o: KeeperInfo2): JsValue =
      JsObject(Seq[Option[(String, JsValue)]](
        if (o.shown) Some("shown" -> JsBoolean(true)) else None,
        if (o.neverOnSite) Some("neverOnSite" -> JsBoolean(true)) else None,
        if (o.keepers.nonEmpty) Some("keepers" -> userWithSocialSerializer.writes(o.keepers)) else None,
        if (o.keeps > 0) Some("keeps" -> JsNumber(o.keeps)) else None,
        if (o.following) Some("following" -> JsBoolean(true)) else None,
        if (o.comments.nonEmpty) Some("comments" -> commentWithBasicUserSerializer.writes(o.comments)) else None,
        if (o.threads.nonEmpty) Some("threads" -> threadInfoSerializer.writes(o.threads)) else None,
        if (o.lastCommentRead.nonEmpty) Some("lastCommentRead" -> Json.toJson(o.lastCommentRead.get)) else None,
        if (o.lastMessageRead.nonEmpty) Some("lastMessageRead" -> Json.toJson(o.lastMessageRead.map(m => m._1.id -> m._2))) else None)
      .flatten)
  }
}

@Singleton
class KeeperInfoLoader @Inject() (
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
    paneDetails: PaneDetails,
    domainClassifier: DomainClassifier,
    userWithSocialRepo: UserWithSocialRepo,
    historyTracker: SliderHistoryTracker,
    searchClient: SearchServiceClient) {

  def load1(userId: Id[User], normalizedUri: String): KeeperInfo1 = {
    db.readOnly { implicit session =>
      val bookmark: Option[Bookmark] = normalizedURIRepo.getByNormalizedUri(normalizedUri).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, userId)
      }
      val host: Option[String] = URI.parse(normalizedUri).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val sensitive = domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption))
      KeeperInfo1(bookmark.map { b => if (b.isPrivate) "private" else "public" }, sensitive.getOrElse(false))
    }
  }

  def load2(userId: Id[User], normalizedUri: String): KeeperInfo2 = {
    val (nUri, shown, neverOnSite, following, comments, threads, lastCommentRead, lastMessageRead) = db.readOnly { implicit session =>
      val nUri = normalizedURIRepo.getByNormalizedUri(normalizedUri)
      val host: Option[String] = URI.parse(normalizedUri).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val neverOnSite: Boolean = domain.map { dom =>
        userToDomainRepo.exists(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW)
      }.getOrElse(false)

      nUri match {
        case Some(uri) =>
          val shown = historyTracker.getMultiHashFilter(userId).mayContain(uri.id.get.id)
          val following = followRepo.get(userId, uri.id.get).isDefined
          val comments = paneDetails.getComments(uri.id.get)
          val threads = paneDetails.getMessageThreadList(userId, uri.id.get)
          val lastCommentRead = commentReadRepo.getByUserAndUri(userId, uri.id.get).map(_.createdAt)
          val lastMessageRead = commentRepo.getMessages(uri.id.get, userId)
              .map(t =>
                commentReadRepo.getByUserAndParent(userId, t.id.get)
                  .map((t.externalId -> _.createdAt))
              ).flatten.toMap

          (nUri, shown, neverOnSite, following, comments, threads, lastCommentRead, lastMessageRead)
        case None =>
          (nUri, false, neverOnSite, false, Nil, Nil, None, Map[ExternalId[Comment], DateTime]())
      }
    }
    val (keepers, keeps) = nUri map { uri =>
      val sharingUserInfo = Await.result(searchClient.sharingUserInfo(userId, uri.id.get), Duration.Inf)
      val socialUsers = db.readOnly { implicit session =>
        sharingUserInfo.sharingUserIds.map(u => userWithSocialRepo.toUserWithSocial(userRepo.get(u))).toSeq
      }
      (socialUsers, sharingUserInfo.keepersEdgeSetSize)
    } getOrElse (Nil, 0)

    KeeperInfo2(shown, neverOnSite, keepers, keeps, following, comments, threads, lastCommentRead, lastMessageRead)
  }
}
