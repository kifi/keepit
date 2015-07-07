package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor, SafeFuture }
import com.keepit.common.controller.UserRequest
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient, UserAgent }
import com.keepit.common.service.IpAddress
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.{ DateTime, Period }
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.{ ExecutionContext, Future }

object UserIpAddressRules {
  val blacklistCompanies = Set("Digital Ocean", "AT&T Wireless", "Verizon Wireless", "Best Buy Co.", "Leaseweb USA", "Nobis Technology Group, LLC").map(_.toLowerCase)
}

case class UserIpAddressEvent(userId: Id[User], ip: IpAddress, userAgent: UserAgent, reportNewClusters: Boolean = true)

class UserIpAddressActor @Inject() (userIpAddressEventLogger: UserIpAddressEventLogger, airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) {

  def receive = {
    case event: UserIpAddressEvent => userIpAddressEventLogger.logUser(event)
    case m => throw new UnsupportedActorMessage(m)
  }
}

class UserIpAddressEventLogger @Inject() (
    db: Database,
    userRepo: UserRepo,
    userIpAddressRepo: UserIpAddressRepo,
    httpClient: HttpClient,
    userStatisticsCommander: UserStatisticsCommander,
    clock: Clock) extends Logging {

  private val ipClusterSlackChannelUrl = "https://hooks.slack.com/services/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"
  private val clusterMemoryTime = Period.weeks(10) // How long back do we look and still consider a user to be part of a cluster

  def logUser(event: UserIpAddressEvent): Unit = {
    if (event.ip.ip.toString.startsWith("10.")) {
      throw new IllegalArgumentException(s"IP Addresses of the form 10.x.x.x are internal ec2 addresses and should not be logged. User ${event.userId}, ip ${event.ip}, agent ${event.userAgent}")
    }
    val now = clock.now()
    val agentType = simplifyUserAgent(event.userAgent)
    if (agentType == "NONE") {
      log.info("[IPTRACK AGENT] Could not parse an agent type out of: " + event.userAgent)
    }
    val model = UserIpAddress(userId = event.userId, ipAddress = event.ip, agentType = agentType)

    val cluster = db.readWrite { implicit session =>
      val currentCluster = userIpAddressRepo.getUsersFromIpAddressSince(event.ip, now.minus(clusterMemoryTime))
      userIpAddressRepo.saveIfNew(model)
      currentCluster.toSet
    }

    if (event.reportNewClusters && !cluster.contains(event.userId) && cluster.size >= 1) {
      log.info("[IPTRACK NOTIFY] Cluster " + cluster + " has new member " + event.userId)
      notifySlackChannelAboutCluster(clusterIp = event.ip, clusterMembers = cluster + event.userId, newUserId = Some(event.userId))
    }
  }

  def simplifyUserAgent(userAgent: UserAgent): String = {
    val agentType = userAgent.typeName.toUpperCase()
    if (agentType.isEmpty) "NONE" else agentType
  }

  private def formatCluster(ip: IpAddress, users: Seq[UserStatistics], newUserId: Option[Id[User]], location: Option[String] = None, company: Option[String] = None): BasicSlackMessage = {
    val clusterDeclaration = Seq(
      Some(s"Found a cluster of ${users.length} at <http://ip-api.com/$ip|$ip>"),
      location.map("I think the company is in " + _),
      company.map("I think the company is '" + _ + "'")
    ).flatten

    val userDeclarations = users.map { stats =>
      val user = stats.user
      val primaryMail = user.primaryEmail.map(_.address).getOrElse("No Primary Mail")
      val userDeclaration = s"<http://admin.kifi.com/admin/user/${user.id.get}|${user.fullName}>\t$primaryMail\tjoined ${user.createdAt}\t${stats.connections} connections\t${stats.librariesCreated}/${stats.librariesFollowed} lib cr/fw\t${stats.privateKeeps}/${stats.publicKeeps} pb/pv keeps\t"
      if (newUserId.contains(user.id.get)) {
        "*" + userDeclaration + " <-- New Member in Cluster!!!*"
      } else userDeclaration
    }

    BasicSlackMessage((clusterDeclaration ++ userDeclarations).mkString("\n"))
  }

  def notifySlackChannelAboutCluster(clusterIp: IpAddress, clusterMembers: Set[Id[User]], newUserId: Option[Id[User]] = None): Unit = {
    log.info("[IPTRACK NOTIFY] Notifying slack channel about " + clusterIp)
    val usersFromCluster = db.readOnlyMaster { implicit session =>
      val userIds = clusterMembers.toSeq
      userRepo.getUsers(userIds).values.toSeq.map { user =>
        userStatisticsCommander.userStatistics(user, Map.empty)
      }
    }
    val ipInfo = httpClient.get(DirectUrl("http://pro.ip-api.com/json/" + clusterIp + "?key=mnU7wRVZAx6BAyP")).json.asOpt[JsObject]
    log.info("[IPTRACK NOTIFY] Retrieved IP geolocation info: " + ipInfo)

    val companyOpt = ipInfo.flatMap(info => (info \ "org").asOpt[String])
    val countryOpt = ipInfo.flatMap(info => (info \ "country").asOpt[String])

    if (heuristicsSayThisClusterIsRelevant(ipInfo)) {
      val msg = formatCluster(clusterIp, usersFromCluster, newUserId, company = companyOpt, location = countryOpt)
      httpClient.post(DirectUrl(ipClusterSlackChannelUrl), Json.toJson(msg))
    }
  }

  private def heuristicsSayThisClusterIsRelevant(ipInfo: Option[JsObject]): Boolean = {
    val companyOpt = ipInfo flatMap { obj => (obj \ "org").asOpt[String] }
    !companyOpt.exists(company => UserIpAddressRules.blacklistCompanies.contains(company.toLowerCase))
  }

  def totalNumberOfLogs(): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.count }
  }

}

class UserIpAddressCommander @Inject() (
    db: Database,
    userIpAddressRepo: UserIpAddressRepo,
    actor: ActorInstance[UserIpAddressActor]) extends Logging {

  def logUser(userId: Id[User], ip: IpAddress, userAgent: UserAgent, reportNewClusters: Boolean = true): Unit = {
    actor.ref ! UserIpAddressEvent(userId, ip, userAgent, reportNewClusters)
  }

  def logUserByRequest[T](request: UserRequest[T]): Unit = {
    val userId = request.userId
    val userAgent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val raw_ip_string = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val ip = IpAddress(raw_ip_string.split(",").head)
    actor.ref ! UserIpAddressEvent(userId, ip, userAgent)
  }

  def countByUser(userId: Id[User]): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.countByUser(userId) }
  }
  def getByUser(userId: Id[User], limit: Int): Seq[UserIpAddress] = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.getByUser(userId, limit) }
  }

  def getUsersByIpAddressSince(ip: IpAddress, time: DateTime): Seq[Id[User]] = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.getUsersFromIpAddressSince(ip, time) }
  }

  def kvPairsToMap[A, B](kvs: Seq[(A, B)]): Map[A, Seq[B]] = {
    kvs.groupBy(_._1).mapValues(_.map(_._2))
  }
  def findSharedIpsByUser(userId: Id[User], limit: Int): Map[IpAddress, Seq[Id[User]]] = {
    val sharedIps = db.readOnlyReplica { implicit session =>
      userIpAddressRepo.findSharedIpsByUser(userId, limit)
    }
    kvPairsToMap(sharedIps)
  }
  def findIpClustersSince(time: DateTime, limit: Int): Seq[IpAddress] = {
    db.readOnlyReplica { implicit session =>
      userIpAddressRepo.findIpClustersSince(time, limit)
    }
  }
}
