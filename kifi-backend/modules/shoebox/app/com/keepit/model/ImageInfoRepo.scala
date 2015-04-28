package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.google.inject.Inject
import com.keepit.common.time.Clock
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.common.time._
import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[ImageInfoRepoImpl])
trait ImageInfoRepo extends Repo[ImageInfo] with SeqNumberFunction[ImageInfo] {

  def doNotUseSave(model: ImageInfo)(implicit session: RWSession): ImageInfo
  def getByUri(id: Id[NormalizedURI])(implicit ro: RSession): Seq[ImageInfo]
  def getByUriWithPriority(id: Id[NormalizedURI], minSize: ImageSize, provider: Option[ImageProvider])(implicit ro: RSession): Option[ImageInfo]

  def getLargestByUriWithPriority(id: Id[NormalizedURI])(implicit ro: RSession): Option[ImageInfo]
}

@Singleton
class ImageInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  uriSummaryCache: URISummaryCache,
  airbrake: AirbrakeNotifier)
    extends DbRepo[ImageInfo] with ImageInfoRepo with SeqNumberDbFunction[ImageInfo] with Logging {

  import db.Driver.simple._

  type RepoImpl = ImageInfoTable
  class ImageInfoTable(tag: Tag) extends RepoTable[ImageInfo](db, tag, "image_info") with SeqNumberColumn[ImageInfo] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url = column[String]("url", O.Nullable)
    def name = column[String]("name", O.NotNull)
    def caption = column[String]("caption", O.Nullable)
    def width = column[Int]("width", O.Nullable)
    def height = column[Int]("height", O.Nullable)
    def sz = column[Int]("sz", O.Nullable)
    def provider = column[ImageProvider]("provider", O.Nullable)
    def format = column[ImageFormat]("format", O.Nullable)
    def priority = column[Int]("priority", O.Nullable)
    def path = column[ImagePath]("path", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, seq, uriId, url.?, name, caption.?, width.?, height.?, sz.?, provider.?, format.?, priority.?, path) <> ((ImageInfo.apply _).tupled, ImageInfo.unapply)
  }

  def table(tag: Tag) = new ImageInfoTable(tag)
  initTable()

  override def deleteCache(model: ImageInfo)(implicit session: RSession): Unit = {
    uriSummaryCache.remove(URISummaryKey(model.uriId))
  }
  override def invalidateCache(model: ImageInfo)(implicit session: RSession): Unit = {
    uriSummaryCache.remove(URISummaryKey(model.uriId))
  }

  def doNotUseSave(model: ImageInfo)(implicit session: RWSession): ImageInfo = super.save(model)

  override def save(model: ImageInfo)(implicit session: RWSession): ImageInfo = {
    val info = if (model.id.isDefined) {
      // We are updating a specific row
      model
    } else {
      // No ID specified. We are saving a new image. Try purging old images first and reuse an existing row if possible
      purgeOldImages(model) match {
        case Some(id) => model.withId(id)
        case None => model
      }
    }
    // setting a negative sequence number for deferred assignment
    val seqNum = deferredSeqNum()
    val toSave = info.copy(seq = seqNum)
    log.info(s"[ImageRepo.save] $toSave")
    super.save(toSave)
  }

  private def purgeOldImages(info: ImageInfo)(implicit session: RWSession): Option[Id[ImageInfo]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    import ImageInfoStates._
    import ImageProvider._

    val now = clock.now()
    val seqNum = deferredSeqNum()
    info.provider match {
      case Some(PAGEPEEKER) =>
        (info.width, info.height) match {
          case (Some(w), Some(h)) =>
            // deactivate images with the same size
            sqlu"update image_info set state=${INACTIVE.value}, seq=${seqNum.value}, updated_at=${now} where uri_id=${info.uriId} and provider=${PAGEPEEKER.value} and state=${ACTIVE.value} and width=$w and height=$h".first
          case _ =>
            log.error(s"no width/height specified for pagepeeker image: $info")
            airbrake.notify(s"no width/height specified for pagepeeker image: $info")
        }
      case Some(EMBEDLY) =>
        info.url match {
          case Some(url) =>
            // deactivate images with the same url or older than a day
            val yesterday = clock.now.minusDays(1)
            //sqlu"update image_info set state=${INACTIVE.value}, seq=${seqNum.value}, updated_at=${now} where uri_id=${info.uriId} and provider=${EMBEDLY.value} and state=${ACTIVE.value} and (updated_at < $yesterday or url = $url)".first
            // deactivate rows with null provider
            sqlu"update image_info set state=${INACTIVE.value}, seq=${seqNum.value}, updated_at=${now} where uri_id=${info.uriId} and provider is null and state=${ACTIVE.value}".first
          case _ =>
            log.error(s"no url specified for embedly image: $info")
            airbrake.notify(s"no url specified for embedly image: $info")
        }
      case _ =>
        log.error(s"no provider specified for image: $info")
        airbrake.notify(s"no provider specified for image: $info")
    }
    // pick the latest inactive row for update
    (for (f <- rows if f.uriId === info.uriId && f.state === INACTIVE) yield f).sortBy(_.updatedAt desc).map(_.id).firstOption
  }

  def getByUri(id: Id[NormalizedURI])(implicit ro: RSession): Seq[ImageInfo] = {
    (for (f <- rows if f.uriId === id && f.state === ImageInfoStates.ACTIVE) yield f).list
  }

  def getByUriWithPriority(id: Id[NormalizedURI], minSize: ImageSize, providerOpt: Option[ImageProvider])(implicit ro: RSession): Option[ImageInfo] = {
    val candidates = providerOpt match {
      case Some(provider) =>
        (for (
          f <- rows if f.uriId === id && f.state === ImageInfoStates.ACTIVE &&
            f.width > minSize.width && f.height > minSize.height && f.provider === provider &&
            f.width < 3000 && f.height < 3000
        ) yield f).list
      case None =>
        (for (
          f <- rows if f.uriId === id && f.state === ImageInfoStates.ACTIVE &&
            f.width > minSize.width && f.height > minSize.height &&
            f.width < 3000 && f.height < 3000
        ) yield f).list
    }
    if (candidates.nonEmpty) {
      Some(candidates.minBy { i =>
        val size = (i.width, i.height) match {
          case (Some(w), Some(h)) => w * h
          case _ => Int.MaxValue
        }
        // This is as weird as it looks. Originally this API returned the smallest image bigger than minSize.
        // This was problematic because we're passing in 0,0 almost always (why?!), so we basically returned icons
        // for everything. Then we returned the largest, which is again problematic because mobile phones can't
        // deal with big images. So now we're returning based on the score below, which optimizes for images
        // around 800x800px
        val sizeScore = Math.abs(800 - Math.sqrt(size)) // lower is better
        // Sort by priority, image size score (lower is better), -id (newer is better)
        (i.priority.getOrElse(Int.MaxValue), sizeScore, -1 * i.id.get.id)
      })
    } else {
      None
    }
  }

  // If this exists after KeepImage migration, tell Andrew to clean up his messes
  def getLargestByUriWithPriority(id: Id[NormalizedURI])(implicit ro: RSession): Option[ImageInfo] = {
    val minSize = ImageSize(150, 150)
    val candidates = (for (
      f <- rows if f.uriId === id && f.state === ImageInfoStates.ACTIVE &&
        f.width > minSize.width && f.height > minSize.height
    ) yield f).list
    if (candidates.nonEmpty) {
      val lowestPrio = candidates.groupBy(_.priority.getOrElse(Int.MaxValue)).minBy(_._1)._2
      val best = lowestPrio.maxBy(c => c.width.getOrElse(0) * c.height.getOrElse(0))
      Some(best)
    } else {
      None
    }
  }
}
