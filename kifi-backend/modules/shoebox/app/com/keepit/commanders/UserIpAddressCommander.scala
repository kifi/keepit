package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.UserRequest
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient, UserAgent }
import com.keepit.common.service.IpAddress
import com.keepit.model._
import org.joda.time.{ DateTime, Period }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

class UserIpAddressCommander @Inject() (
    db: Database,
    implicit val defaultContext: ExecutionContext,
    httpClient: HttpClient,
    userIpAddressRepo: UserIpAddressRepo,
    userRepo: UserRepo) extends Logging {

  private val ipClusterSlackChannelUrl = "https://hooks.slack.com/services/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"
  private val clusterMemoryTime = Period.weeks(10) // How long back do we look and still consider a user to be part of a cluster

  def simplifyUserAgent(userAgent: UserAgent): String = {
    val agentType = userAgent.typeName.toUpperCase()
    if (agentType.isEmpty) "NONE" else agentType
  }

  def logUser(userId: Id[User], ip: IpAddress, userAgent: UserAgent): Future[Unit] = SafeFuture {
    if (ip.ip.toString.startsWith("10.")) {
      throw new IllegalArgumentException("IP Addresses of the form 10.x.x.x are internal ec2 addresses and should not be logged")
    }
    val now = DateTime.now()
    val agentType = simplifyUserAgent(userAgent)
    if (agentType == "NONE") {
      log.info("[RPB] Could not parse an agent type out of: " + userAgent)
    }
    val model = UserIpAddress(None, now, now, UserIpAddressStates.ACTIVE, userId, ip, agentType)

    val oldCluster = db.readWrite { implicit session =>
      val currentCluster = userIpAddressRepo.getUsersFromIpAddressSince(ip, now.minus(clusterMemoryTime))
      userIpAddressRepo.saveIfNew(model)
      currentCluster
    }

    if (!oldCluster.isEmpty && !oldCluster.contains(userId)) {
      notifySlackChannelAboutCluster(ip)
    }
  }

  def logUserByRequest[T](request: UserRequest[T]): Unit = {
    val userId = request.userId
    val userAgent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val raw_ip_string = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val ip = IpAddress(raw_ip_string.split(",").head)
    logUser(userId, ip, userAgent)
  }

  def formatCluster(ip: IpAddress, users: Seq[User]): BasicSlackMessage = {
    val userDeclarations = (for { u <- users } yield s"<http://admin.kifi.com/admin/user/${u.id.get}|${u.fullName}>").toList
    BasicSlackMessage((s"Found a cluster of ${users.length} at $ip" :: userDeclarations).mkString("\n"))
  }

  def notifySlackChannelAboutCluster(clusterIp: IpAddress): Unit = {
    val usersFromCluster = db.readOnlyReplica { implicit session =>
      val userIds = userIpAddressRepo.getUsersFromIpAddressSince(clusterIp, DateTime.now.minus(clusterMemoryTime))
      userRepo.getUsers(userIds).values.toSeq
    }
    val msg = formatCluster(clusterIp, usersFromCluster)
    httpClient.post(DirectUrl(ipClusterSlackChannelUrl), Json.toJson(msg))
  }

  def totalNumberOfLogs(): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.count }
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
