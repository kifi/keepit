package com.keepit.classify

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import java.net.{ConnectException, URLEncoder}
import java.security.MessageDigest
import java.util.UUID

import com.coremedia.iso.Hex.encodeHex
import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.strings._

import akka.pattern.ask
import play.api.libs.concurrent.Execution.Implicits._

private case class FetchDomainInfo(domain: String)

private[classify] class DomainClassificationActor @Inject() (
  healthcheck: HealthcheckPlugin,
  db: Database,
  client: HttpClient,
  updater: SensitivityUpdater,
  domainRepo: DomainRepo,
  tagRepo: DomainTagRepo,
  domainToTagRepo: DomainToTagRepo)
    extends FortyTwoActor(healthcheck) {

  private final val KEY = "42go42"

  private val servers = Seq("thor.komodia.com", "optimus.komodia.com", "rodimus.komodia.com")

  private def getTagNames(url: String): Future[Seq[DomainTagName]] = {
    servers.tail.foldLeft(getTagNames(servers.head, url))((result, nextServer) => result recoverWith {
      case _: ConnectException => getTagNames(nextServer, url)
    })
  }

  private def getTagNames(server: String, url: String): Future[Seq[DomainTagName]] = {
    // see http://www.komodia.com/wiki/index.php/URL_server_protocol
    val md = MessageDigest.getInstance("MD5")
    val guid = UUID.randomUUID.toString.toUpperCase
    val md5 = md.digest(KEY + guid + KEY)
    val id = encodeHex(md5).toLowerCase
    val encodedUrl = URLEncoder.encode(url, UTF8)
    client.getFuture(s"http://$server/url.php?version=w11&guid=$guid&id=$id&url=$encodedUrl").map { resp =>
      (resp.body.split("~", 2).toList match {
        case ("FM" | "FR") :: tagString :: Nil =>
          // response is comma separated, but includes commas inside parentheses
          """\(([^)]*)\)""".r.replaceAllIn(tagString, _.group(0).replace(',', '\u02bd'))
            .split(",")
            .map(_.replace('\u02bd', ','))
            .map(DomainTagName(_))
            .toSeq
        case _ => Seq()
      }).filterNot(DomainTagName.isBlacklisted)
    }
  }

  def receive = {
    case FetchDomainInfo(hostname) =>
      val domain = db.readWrite(attempts = 3) { implicit s =>
        domainRepo.get(hostname, excludeState = None) match {
          case Some(d) if d.state != DomainStates.ACTIVE => domainRepo.save(d.withState(DomainStates.ACTIVE))
          case Some(d) => d
          case None => domainRepo.save(Domain(hostname = hostname))
        }
      }
      val res: Option[Boolean] = domain.sensitive orElse {
        // check again to make sure a previous FetchDomainInfo hasn't filled in the sensitivity
        val tagNames = Await.result(getTagNames(hostname), 100 seconds)
        val tagIds = db.readWrite { implicit s =>
          tagNames.map { name =>
            (tagRepo.get(name, excludeState = None) match {
              case Some(tag) if tag.state != DomainTagStates.ACTIVE =>
                tagRepo.save(tag.withState(DomainTagStates.ACTIVE))
              case Some(tag) => tag
              case None => tagRepo.save(DomainTag(name = name))
            }).id.get
          }.toSet
        }
        db.readWrite { implicit s =>
          val existingTagRelationships = domainToTagRepo.getByDomain(domain.id.get, excludeState = None)
          for (r <- existingTagRelationships if r.state != DomainToTagStates.ACTIVE && tagIds.contains(r.tagId)) {
            domainToTagRepo.save(r.withState(DomainToTagStates.ACTIVE))
          }
          domainToTagRepo.insertAll((tagIds -- existingTagRelationships.map(_.tagId)).map { tagId =>
            DomainToTag(domainId = domain.id.get, tagId = tagId)
          }.toSeq)
        }
        db.readWrite { implicit s =>
          // since sensitive had a value of None before, we always need to recompute even if nothing changed
          updater.calculateSensitivity(domain)
        }
      }
      sender ! res.getOrElse(false)
  }
}

@ImplementedBy(classOf[DomainClassifierImpl])
trait DomainClassifier {
  def isSensitive(domain: String): Either[Future[Boolean], Boolean]
}

class DomainClassifierImpl @Inject()(
  actorFactory: ActorFactory[DomainClassificationActor],
  db: Database,
  domainRepo: DomainRepo,
  updater: SensitivityUpdater)
    extends DomainClassifier {

  private lazy val actor = actorFactory.get()

  private val splitPattern = """\.""".r

  def isSensitive(hostname: String): Either[Future[Boolean], Boolean] = {
    val domainParts = splitPattern.split(hostname.toLowerCase).toSeq
    if (domainParts.size == 1) {
      // this is probably a local domain on the network which we want to keep private
      Right(true)
    } else {
      val domainName = domainParts.dropWhile(_ == "www").mkString(".")
      val domainOpt = db.readOnly { implicit s => domainRepo.get(domainName) }
      domainOpt.flatMap { domain =>
        domain.sensitive.orElse(db.readWrite { implicit s => updater.calculateSensitivity(domain) })
      } match {
        case Some(sensitive) => Right(sensitive)
        case None => Left(actor.ask(FetchDomainInfo(domainName))(1 minute).mapTo[Boolean])
      }
    }
  }
}
